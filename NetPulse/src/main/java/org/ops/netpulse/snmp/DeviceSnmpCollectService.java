package org.ops.netpulse.snmp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.Device.DeviceStatus;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.service.DeviceSshCollectService;
import org.ops.netpulse.service.DeviceStatsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按「设备管理」中已配置 SNMP 的网络设备（路由器/交换机/防火墙等）采集 CPU/内存。
 * 仅网络设备采集 SNMP，Linux 服务器不采集；离线设备不采集，避免无效请求与重复写库。
 * 支持直采（不经过 Redis）：实时指标接口通过 getStatsDirect 直接采集；snmp.use-redis=true 时定时/单次采集结果才写入 Redis。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "snmp.collect.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DeviceSnmpCollectService {

    private final DeviceRepository deviceRepository;
    @Qualifier("snmpStringRedisTemplate")
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** SNMP 采集失败时回退到 SSH/Telnet 采集，可选依赖（ssh-collect.enabled=true 时才有） */
    @Lazy
    private final org.springframework.beans.factory.ObjectProvider<DeviceSshCollectService> sshCollectProvider;

    @Value("${snmp.firewall.timeout-ms:5000}")
    private int timeoutMs;
    @Value("${snmp.firewall.retries:2}")
    private int retries;
    /** 为 true 时定时/单次采集结果写入 Redis；为 false 时仅直采，不经过 Redis。默认 false。 */
    @Value("${snmp.use-redis:false}")
    private boolean useRedis;

    private static final String REDIS_DEVICE_PREFIX = "netpulse:snmp:device";
    private static final String KEY_LAST = "lastCollectTime";

    @Scheduled(
            initialDelayString = "${snmp.collect.interval-ms:300000}",
            fixedDelayString = "${snmp.collect.interval-ms:300000}"
    )
    public void collectAllDevicesAndSaveToRedis() {
        List<Device> devices = deviceRepository.findByDeletedFalseAndSnmpConfigured();
        if (devices.isEmpty()) {
            log.debug("无已配置 SNMP 的设备，跳过 SNMP 采集；对在线且已配置 SSH/Telnet 的网络设备尝试 SSH/Telnet 采集");
            DeviceSshCollectService ssh = sshCollectProvider.getIfAvailable();
            if (ssh != null) {
                int n = ssh.collectAllNow();
                if (n > 0) log.info("无 SNMP 设备时已通过 SSH/Telnet 采集 {} 台在线网络设备", n);
            }
            return;
        }

        for (Device d : devices) {
            // Linux 服务器统一通过 Telegraf/SSH 采集，本服务仅采集网络设备（路由器/交换机/防火墙等）
            if (d.getType() == Device.DeviceType.server) {
                continue;
            }
            // 若设备已标记为离线，则本轮不再对其发起 SNMP 采集，避免无效请求
            if (d.getStatus() == DeviceStatus.offline) {
                continue;
            }
            try {
                collectOneAndSave(d);
            } catch (Exception e) {
                String protocol = (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) ? "SNMPv3" : "SNMPv2c";
                log.warn("设备 SNMP 采集失败 id={} ip={} name={} [{}]: {}，尝试改用 SSH/Telnet 采集",
                        d.getId(), d.getIp(), d.getName(), protocol, e.getMessage());
                tryFallbackToSsh(d);
            }
        }
    }

    /** SNMP 失败时若设备已配置用户名密码且启用 SSH/Telnet 采集，则用 SSH/Telnet 采集一次并写 Redis。Web SSH 曾连不上的设备不尝试回退。返回 true 表示回退采集成功。 */
    private boolean tryFallbackToSsh(Device d) {
        if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) {
            log.info("SNMP 失败后未回退 SSH/Telnet：设备 id={} ip={} 未配置 SSH/Telnet 用户名或密码", d.getId(), d.getIp());
            return false;
        }
        DeviceSshCollectService ssh = sshCollectProvider.getIfAvailable();
        if (ssh == null) {
            log.warn("SNMP 失败后未回退 SSH/Telnet：SSH/Telnet 采集未启用（请设置 ssh-collect.enabled=true 并重启） id={} ip={}", d.getId(), d.getIp());
            return false;
        }
        if (ssh.isWebSshUnreachable(d.getId())) {
            log.debug("SNMP 失败后跳过 SSH 回退：设备 id={} ip={} Web SSH 曾连不上，暂不采集", d.getId(), d.getIp());
            return false;
        }
        log.info("SNMP 失败，正在回退 SSH/Telnet 采集 id={} ip={}", d.getId(), d.getIp());
        try {
            if (ssh.collectOne(d.getId())) {
                log.info("SNMP 失败后已改用 SSH/Telnet 采集成功 id={} ip={}", d.getId(), d.getIp());
                return true;
            }
            log.warn("SNMP 失败后 SSH/Telnet 采集未成功（设备可能离线或非网络设备） id={} ip={}", d.getId(), d.getIp());
            return false;
        } catch (Exception ex) {
            log.warn("SNMP 失败后 SSH/Telnet 回退也失败 id={} ip={}: {}", d.getId(), d.getIp(), ex.getMessage());
            return false;
        }
    }

    /**
     * 根据 sysObjectID 批量识别已有设备的厂商（仅填充当前 vendor 为空的设备）。
     * 仅对网络设备且在线设备执行 SNMP，Linux 服务器与离线设备不采集。
     */
    public int autoDetectVendorsForAllDevices() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        int updated = 0;
        for (Device d : devices) {
            if (d.getVendor() != null && !d.getVendor().isBlank()) continue;
            if (d.getType() == Device.DeviceType.server) continue;
            if (d.getStatus() == DeviceStatus.offline) continue;
            try {
                String ip = d.getIp();
                if (ip == null || ip.isBlank()) continue;
                List<String> oids = List.of(SnmpUtils.OID_SYS_OBJECT_ID);
                Map<String, String> meta = snmpGet(d, oids);
                String sysObjectId = meta.getOrDefault(SnmpUtils.OID_SYS_OBJECT_ID, "");
                if (sysObjectId == null || sysObjectId.isBlank()) continue;
                String lower = sysObjectId.toLowerCase();
                String vendor = null;
                if (lower.contains(".2011.")) {
                    vendor = "huawei";
                } else if (lower.contains(".9.")) {
                    vendor = "cisco";
                } else if (lower.contains(".25506.")) {
                    vendor = "h3c";
                } else if (lower.contains(".4881.")) {
                    vendor = "ruijie";
                }
                if (vendor != null) {
                    d.setVendor(vendor);
                    deviceRepository.save(d);
                    updated++;
                    log.info("自动识别厂商成功 id={} ip={} sysObjectId={} vendor={}", d.getId(), ip, sysObjectId, vendor);
                }
            } catch (Exception e) {
                log.debug("自动识别厂商失败 id={} ip={}: {}", d.getId(), d.getIp(), e.getMessage());
            }
        }
        return updated;
    }

    /**
     * 直采：对已配置 SNMP 的设备现场采集 CPU/内存，不经过 Redis。供实时指标接口使用。
     * @param devices 全部设备列表，内部会过滤出已配置 SNMP 的
     * @return deviceId -> DeviceStats（仅包含成功采集到 CPU 或内存的设备）
     */
    public Map<Long, DeviceStatsService.DeviceStats> getStatsDirect(List<Device> devices) {
        Map<Long, DeviceStatsService.DeviceStats> out = new HashMap<>();
        if (devices == null) return out;
        for (Device d : devices) {
            if (d.getId() == null) continue;
            // 直采也仅针对网络设备，Linux 服务器不通过 SNMP 采集
            if (d.getType() == Device.DeviceType.server) continue;
            // 离线设备不再尝试 SNMP 直采，避免无效请求
            if (d.getStatus() == DeviceStatus.offline) continue;
            if (d.getSnmpCommunity() == null || d.getSnmpCommunity().isBlank()) {
                if (d.getSnmpSecurity() == null || d.getSnmpSecurity().isBlank()) continue;
            }
            try {
                DeviceStatsService.DeviceStats stats = collectOneToStats(d);
                if (stats != null && (stats.getCpuPercent() != null || stats.getMemoryPercent() != null)) {
                    out.put(d.getId(), stats);
                    log.debug("SNMP 直采成功 id={} ip={} name={} cpu={}% memory={}%",
                            d.getId(), d.getIp(), d.getName(), stats.getCpuPercent(), stats.getMemoryPercent());
                }
            } catch (Exception e) {
                log.debug("SNMP 直采失败 id={} ip={}: {}", d.getId(), d.getIp(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * 仅检测设备 SNMP 是否可达（取 sysName 一个 OID），用于连通性诊断。
     * @param d 已配置 SNMP 的设备
     * @return 能拿到响应为 true，超时/失败为 false
     */
    public boolean snmpReachable(Device d) {
        if (d == null || d.getIp() == null || d.getIp().isBlank()) return false;
        // Linux 服务器不做 SNMP 连通性检测
        if (d.getType() == Device.DeviceType.server) return false;
        if (d.getSnmpCommunity() == null || d.getSnmpCommunity().isBlank()) {
            if (d.getSnmpSecurity() == null || d.getSnmpSecurity().isBlank()) return false;
        }
        try {
            String ip = d.getIp();
            int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
            String[] oid = new String[]{SnmpUtils.OID_SYS_NAME};
            Map<String, String> result;
            if (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) {
                SnmpV3Params v3 = objectMapper.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
                if (v3.getUsername() == null || v3.getUsername().isBlank()) return false;
                result = SnmpUtils.snmpV3Get(ip, port, timeoutMs, retries,
                        v3.getUsername(),
                        v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                        v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                        oid);
            } else {
                String community = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()) ? d.getSnmpCommunity() : "public";
                result = SnmpUtils.snmpV2cGet(ip, port, community, timeoutMs, retries, oid);
            }
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            log.debug("SNMP 连通检测失败 id={} ip={}: {}", d.getId(), d.getIp(), e.getMessage());
            return false;
        }
    }

    /**
     * 单设备实时采集（供接口「立即采集」调用）。snmp.use-redis=true 时写入 Redis。
     * @param deviceId 设备 ID
     * @return 是否执行成功（设备存在且已配置 SNMP）
     */
    public boolean collectOne(Long deviceId) {
        if (deviceId == null) return false;
        return deviceRepository.findById(deviceId)
                // 手动 SNMP 采集仅适用于网络设备
                .filter(d -> d.getType() != Device.DeviceType.server)
                .filter(d -> d.getStatus() != Device.DeviceStatus.offline) // 离线设备不执行采集
                .filter(d -> d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()
                        || d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank())
                .map(d -> {
                    try {
                        collectOneAndSave(d);
                        return true;
                    } catch (Exception e) {
                        log.warn("设备 id={} SNMP 采集失败: {}，尝试改用 SSH/Telnet 采集", deviceId, e.getMessage());
                        return tryFallbackToSsh(d);
                    }
                })
                .orElse(false);
    }

    /**
     * 单设备采集并转为 DeviceStats，不写 Redis。
     * CPU 与内存分两次 SNMP 请求，避免一次请求 OID 过多导致超时（主机名能取到但 CPU/内存超时时可改善）。
     */
    private DeviceStatsService.DeviceStats collectOneToStats(Device d) {
        String ip = d.getIp();
        if (ip == null || ip.isBlank()) return null;
        List<String> cpuOids = SnmpUtils.getCpuOidsToTry(d.getVendor(), d.getType() != null ? d.getType().toValue() : null);
        List<String> memOids = SnmpUtils.getMemoryOidsToTry(d.getVendor(), d.getType() != null ? d.getType().toValue() : null);

        Map<String, String> cpuResult = null;
        Map<String, String> memResult = null;
        try {
            if (!cpuOids.isEmpty()) {
                cpuResult = snmpGet(d, cpuOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} CPU OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }
        try {
            if (!memOids.isEmpty()) {
                memResult = snmpGet(d, memOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 内存 OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }

        String cpuStr = "-";
        if (cpuResult != null) {
            for (String oid : cpuOids) {
                String v = cpuResult.get(oid);
                if (v != null && !v.isEmpty() && !v.equals("-") && v.matches("\\d+")) {
                    cpuStr = v;
                    break;
                }
            }
        }
        String memoryStr = (memResult != null) ? parseMemoryPercent(memResult, memOids) : "-";

        Double cpuPercent = parsePercent(cpuStr);
        Double memPercent = parsePercent(memoryStr);
        if (cpuPercent == null && memPercent == null) return null;

        DeviceStatsService.DeviceStats stats = new DeviceStatsService.DeviceStats();
        stats.setCpuPercent(cpuPercent);
        stats.setMemoryPercent(memPercent);
        stats.setUpdatedAt(Instant.now().toEpochMilli());
        return stats;
    }

    /** 对设备执行一次 SNMP GET（仅传入的 OID 列表），v2c/v3 根据设备配置。 */
    private Map<String, String> snmpGet(Device d, List<String> oids) throws Exception {
        if (oids == null || oids.isEmpty()) return Map.of();
        String ip = d.getIp();
        int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
        if (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) {
            SnmpV3Params v3 = objectMapper.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
            if (v3.getUsername() == null || v3.getUsername().isBlank()) return Map.of();
            return SnmpUtils.snmpV3Get(ip, port, timeoutMs, retries,
                    v3.getUsername(),
                    v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                    v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                    oids.toArray(new String[0]));
        }
        String community = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()) ? d.getSnmpCommunity() : "public";
        return SnmpUtils.snmpV2cGet(ip, port, community, timeoutMs, retries, oids.toArray(new String[0]));
    }

    private static Double parsePercent(String v) {
        if (v == null || v.isEmpty() || "-".equals(v.trim())) return null;
        try {
            double d = Double.parseDouble(v.trim());
            if (d >= 0 && d <= 100) return d;
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private void collectOneAndSave(Device d) {
        String ip = d.getIp();
        if (ip == null || ip.isBlank()) return;
        List<String> metaOids = List.of(
                SnmpUtils.OID_SYS_NAME,
                SnmpUtils.OID_SYS_DESCR,
                SnmpUtils.OID_SYS_OBJECT_ID,
                SnmpUtils.OID_IF_NUMBER
        );
        List<String> cpuOids = SnmpUtils.getCpuOidsToTry(d.getVendor(), d.getType() != null ? d.getType().toValue() : null);
        List<String> memOids = SnmpUtils.getMemoryOidsToTry(d.getVendor(), d.getType() != null ? d.getType().toValue() : null);

        String sysName = "-", sysDescr = "-", sysObjectId = "-", ifNumber = "-";
        try {
            Map<String, String> meta = snmpGet(d, metaOids);
            sysName = meta.getOrDefault(SnmpUtils.OID_SYS_NAME, "-");
            sysDescr = meta.getOrDefault(SnmpUtils.OID_SYS_DESCR, "-");
            sysObjectId = meta.getOrDefault(SnmpUtils.OID_SYS_OBJECT_ID, "-");
            ifNumber = meta.getOrDefault(SnmpUtils.OID_IF_NUMBER, "-");
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 元数据 OID 请求失败: {}", d.getId(), ip, e.getMessage());
        }

        // 根据 sysObjectID 自动识别厂商（2011=Huawei，9=Cisco，25506=H3C，4881=Ruijie），仅在未手工填写 vendor 时更新
        if (d.getVendor() == null || d.getVendor().isBlank()) {
            if (sysObjectId != null && !sysObjectId.isBlank()) {
                String lower = sysObjectId.toLowerCase();
                if (lower.contains(".2011.")) {
                    d.setVendor("huawei");
                } else if (lower.contains(".9.")) {
                    d.setVendor("cisco");
                } else if (lower.contains(".25506.")) {
                    d.setVendor("h3c");
                } else if (lower.contains(".4881.")) {
                    d.setVendor("ruijie");
                }
            }
        }

        String cpu = "-";
        try {
            if (!cpuOids.isEmpty()) {
                Map<String, String> cpuResult = snmpGet(d, cpuOids);
                for (String oid : cpuOids) {
                    String v = cpuResult.get(oid);
                    if (v != null && !v.isEmpty() && !v.equals("-") && v.matches("\\d+")) {
                        cpu = v;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} CPU OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }

        String memory = "-";
        try {
            if (!memOids.isEmpty()) {
                Map<String, String> memResult = snmpGet(d, memOids);
                memory = parseMemoryPercent(memResult, memOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 内存 OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }

        if (redisTemplate != null && ip != null && !ip.isBlank()) {
            String key = REDIS_DEVICE_PREFIX + ":ip:" + ip;
            String lastCollectTime = String.valueOf(Instant.now().toEpochMilli());
            try {
                // 覆盖写入：先删后写，不追加，避免残留旧字段
                redisTemplate.delete(key);
                redisTemplate.opsForHash().put(key, "ip", ip);
                redisTemplate.opsForHash().put(key, "sysName", sysName);
                redisTemplate.opsForHash().put(key, "sysDescr", sysDescr);
                redisTemplate.opsForHash().put(key, "ifNumber", ifNumber);
                redisTemplate.opsForHash().put(key, "cpu", cpu);
                redisTemplate.opsForHash().put(key, "memory", memory);
                redisTemplate.opsForHash().put(key, "name", d.getName() != null ? d.getName() : "-");
                redisTemplate.opsForHash().put(key, KEY_LAST, lastCollectTime);
                if (useRedis) {
                    log.info("SNMP 采集成功 设备 id={} ip={} name={} | sysName={} ifNumber={} cpu={} memory={} | 已写入 Redis key={}",
                            d.getId(), ip, d.getName(), sysName, ifNumber, cpu, memory, key);
                }
            } catch (Exception e) {
                log.error("设备 id={} ip={} 写入 Redis 失败: {}", d.getId(), ip, e.getMessage());
            }
        }
    }

    /** 从采集结果解析内存使用率：先取 0–100 的单项值（如华为），再尝试 Cisco used/(used+free)、HR used/size */
    private static String parseMemoryPercent(Map<String, String> result, List<String> memOids) {
        for (String oid : memOids) {
            String v = result.get(oid);
            if (v != null && !v.isEmpty() && !v.equals("-") && v.matches("\\d+")) {
                int n = Integer.parseInt(v);
                if (n >= 0 && n <= 100) return v;
            }
        }
        String ciscoUsedStr = result.get(SnmpUtils.OID_MEM_CISCO_USED);
        String ciscoFreeStr = result.get(SnmpUtils.OID_MEM_CISCO_FREE);
        if (ciscoUsedStr != null && ciscoFreeStr != null && ciscoUsedStr.matches("\\d+") && ciscoFreeStr.matches("\\d+")) {
            long used = Long.parseLong(ciscoUsedStr);
            long free = Long.parseLong(ciscoFreeStr);
            long total = used + free;
            if (total > 0) {
                int pct = (int) Math.round(used * 100.0 / total);
                return String.valueOf(Math.min(100, Math.max(0, pct)));
            }
        }
        String usedStr = result.get(SnmpUtils.OID_MEM_HR_USED);
        String sizeStr = result.get(SnmpUtils.OID_MEM_HR_SIZE);
        if (usedStr != null && sizeStr != null && usedStr.matches("\\d+") && sizeStr.matches("\\d+")) {
            long used = Long.parseLong(usedStr);
            long size = Long.parseLong(sizeStr);
            if (size > 0) {
                int pct = (int) Math.round(used * 100.0 / size);
                return String.valueOf(Math.min(100, Math.max(0, pct)));
            }
        }
        return "-";
    }
}
