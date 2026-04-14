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

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按「设备管理」中已配置 SNMP 的网络设备（路由器/交换机/防火墙等）采集 CPU/内存。
 * 仅网络设备采集 SNMP，Linux 服务器不采集。
 * 注意：不再按设备在线/离线状态跳过采集，避免网络设备禁 ICMP 时被误判 offline 导致 SNMP 指标长期为空。
 * 支持直采（不经过 Redis）：实时指标接口通过 getStatsDirect 直接采集；snmp.use-redis=true 时定时/单次采集结果才写入 Redis。
 * 流程：先取 sysDescr/sysObjectID → {@link SnmpUtils#resolveVendorForOids} 自动判厂商 → 按厂商选 CPU/内存 OID → 可选 WALK ifHC* 汇总接口流量 → 写入 Redis；
 * SNMP 抛错或（可配置）CPU/内存均未取到值时回退 {@link DeviceSshCollectService} SSH/Telnet 补采，与 SNMP 同 key 合并。
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
    /** 是否 WALK IF-MIB ifHCInOctets/ifHCOutOctets 并汇总写入 Redis（接口流量累积字节）。 */
    @Value("${snmp.collect.if-traffic:true}")
    private boolean collectIfTraffic;
    @Value("${snmp.collect.if-traffic-max-rows:256}")
    private int ifTrafficMaxRows;
    /** 指定设备 IP 时输出该设备流量 OID 的 WALK 原始返回（空=关闭）。 */
    @Value("${snmp.collect.debug-traffic-ip:}")
    private String debugTrafficIp;
    /**
     * SNMP 已写入 Redis（可有流量等）但 CPU、内存均未解析到有效值时，是否再尝试 SSH/Telnet 采集并合并写入同一 Redis key。
     * 此回退不依赖「SNMP 抛异常」；模拟器常仅有接口 MIB 而无 CPU/内存 OID 时依赖此项。
     */
    @Value("${snmp.collect.fallback-ssh-when-cpu-mem-missing:true}")
    private boolean fallbackSshWhenCpuMemMissing;

    private static final String REDIS_DEVICE_PREFIX = "netpulse:snmp:device";
    private static final String KEY_LAST = "lastCollectTime";
    /** 从 "Gauge32: 12"、"5 %"、"CPU=23.6" 等文本中提取第一个数值（0~100）。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    /** 单次 SNMP GET 最大 varbind 数，避免旧版/eNSP 设备拒绝大包响应 */
    private static final int SNMP_GET_MAX_VARS_PER_PDU = 4;
    /** WALK 取 CPU/内存百分比时的最大行数（表项索引多时需略大） */
    private static final int SNMP_WALK_PERCENT_MAX_ROWS = 48;

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
            try {
                collectOneAndSave(d);
            } catch (Exception e) {
                String protocol = (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) ? "SNMPv3" : "SNMPv2c";
                log.warn("设备 SNMP 采集失败 id={} ip={} name={} [{}]: {}，尝试改用 SSH/Telnet 采集",
                        d.getId(), d.getIp(), d.getName(), protocol, e.getMessage());
                tryFallbackToSsh(d, true);
            }
        }
    }

    /**
     * SNMP 失败或 CPU/内存缺失时，用 SSH/Telnet 补采并写 Redis（与 SNMP 同 key 合并字段）。
     *
     * @param respectWebSshUnreachable true：若 Web SSH 近期失败过则跳过（与旧行为一致，避免对已知不可达设备反复 exec）；
     *                                 false：仍尝试 exec（批量通道与交互式 Web SSH 不同，SNMP 仅有流量无 CPU/内存时常需此项）
     */
    private boolean tryFallbackToSsh(Device d, boolean respectWebSshUnreachable) {
        if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) {
            log.info("未回退 SSH/Telnet：设备 id={} ip={} 未配置 SSH/Telnet 用户名或密码", d.getId(), d.getIp());
            return false;
        }
        DeviceSshCollectService ssh = sshCollectProvider.getIfAvailable();
        if (ssh == null) {
            log.warn("未回退 SSH/Telnet：SSH/Telnet 采集未启用（请设置 ssh-collect.enabled=true 并重启） id={} ip={}", d.getId(), d.getIp());
            return false;
        }
        if (respectWebSshUnreachable && ssh.isWebSshUnreachable(d.getId())) {
            log.debug("跳过 SSH 回退：设备 id={} ip={} Web SSH 曾连不上（respectWebSshUnreachable=true）", d.getId(), d.getIp());
            return false;
        }
        log.info("正在用 SSH/Telnet 补采 id={} ip={}（respectWebSshUnreachable={}）", d.getId(), d.getIp(), respectWebSshUnreachable);
        try {
            if (ssh.collectOne(d.getId())) {
                log.info("SSH/Telnet 补采成功 id={} ip={}", d.getId(), d.getIp());
                return true;
            }
            log.warn("SSH/Telnet 补采未成功 id={} ip={}", d.getId(), d.getIp());
            return false;
        } catch (Exception ex) {
            log.warn("SSH/Telnet 补采失败 id={} ip={}: {}", d.getId(), d.getIp(), ex.getMessage());
            return false;
        }
    }

    /**
     * 根据 sysDescr（优先）与 sysObjectID 批量识别厂商（仅填充当前 vendor 为空的设备）。
     * 仅对网络设备执行 SNMP，Linux 服务器不采集。
     */
    public int autoDetectVendorsForAllDevices() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        int updated = 0;
        for (Device d : devices) {
            if (d.getVendor() != null && !d.getVendor().isBlank()) continue;
            if (d.getType() == Device.DeviceType.server) continue;
            try {
                String ip = d.getIp();
                if (ip == null || ip.isBlank()) continue;
                List<String> oids = List.of(SnmpUtils.OID_SYS_OBJECT_ID, SnmpUtils.OID_SYS_DESCR);
                Map<String, String> meta = snmpGet(d, oids);
                String sysObjectId = meta.getOrDefault(SnmpUtils.OID_SYS_OBJECT_ID, "");
                String sysDescr = meta.getOrDefault(SnmpUtils.OID_SYS_DESCR, "");
                String vendor = SnmpUtils.resolveVendorForOids(sysDescr, sysObjectId, null);
                if (vendor != null) {
                    String vendorLabel = SnmpUtils.vendorKeyToUiLabel(vendor);
                    String savedVendor = (vendorLabel != null && !vendorLabel.isBlank()) ? vendorLabel : vendor;
                    if (d.getId() != null) {
                        deviceRepository.updateVendorById(d.getId(), savedVendor);
                    }
                    updated++;
                    log.info("自动识别厂商成功 id={} ip={} vendor={} sysDescr前缀={} sysObjectId={}",
                            d.getId(), ip, savedVendor,
                            sysDescr.length() > 48 ? sysDescr.substring(0, 48) + "…" : sysDescr,
                            sysObjectId);
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
                .filter(d -> d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()
                        || d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank())
                .map(d -> {
                    try {
                        collectOneAndSave(d);
                        // 用户点击「SNMP采集」时，额外执行一次 SSH/Telnet 单设备采集，
                        // 用于在模拟器场景下及时补齐 CPU/内存（即使 SNMP 仅有流量）。
                        tryFallbackToSsh(d, false);
                        return true;
                    } catch (Exception e) {
                        log.warn("设备 id={} SNMP 采集失败: {}，尝试改用 SSH/Telnet 采集", deviceId, e.getMessage());
                        return tryFallbackToSsh(d, true);
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
        String sysDescr = "-";
        String sysObjectId = "-";
        try {
            Map<String, String> meta = snmpGet(d, List.of(SnmpUtils.OID_SYS_DESCR, SnmpUtils.OID_SYS_OBJECT_ID));
            sysDescr = meta.getOrDefault(SnmpUtils.OID_SYS_DESCR, "-");
            sysObjectId = meta.getOrDefault(SnmpUtils.OID_SYS_OBJECT_ID, "-");
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 直采读 sysDescr 失败: {}", d.getId(), ip, e.getMessage());
        }
        String oidVendor = SnmpUtils.resolveVendorForOids(sysDescr, sysObjectId, d.getVendor());
        String typeVal = d.getType() != null ? d.getType().toValue() : null;
        List<String> cpuOids = SnmpUtils.getCpuOidsToTry(oidVendor, typeVal, sysObjectId);
        List<String> memOids = SnmpUtils.getMemoryOidsToTry(oidVendor, typeVal, sysObjectId);

        Map<String, String> cpuResult = null;
        Map<String, String> memResult = null;
        try {
            if (!cpuOids.isEmpty()) {
                cpuResult = snmpGetMergeBatches(d, cpuOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} CPU OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }
        try {
            if (!memOids.isEmpty()) {
                memResult = snmpGetMergeBatches(d, memOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 内存 OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }

        String cpuStr = pickFirstCpuPercent(cpuResult, cpuOids);
        String memoryStr = (memResult != null) ? parseMemoryPercent(memResult, memOids) : "-";
        if ("-".equals(memoryStr)) {
            String ciscoMem = walkCiscoMemoryPoolPercent(d);
            if (ciscoMem != null) memoryStr = ciscoMem;
        }

        Double cpuPercent = parsePercent(cpuStr);
        Double memPercent = parsePercent(memoryStr);
        if (cpuPercent == null && memPercent == null) return null;

        DeviceStatsService.DeviceStats stats = new DeviceStatsService.DeviceStats();
        stats.setCpuPercent(cpuPercent);
        stats.setMemoryPercent(memPercent);
        stats.setUpdatedAt(Instant.now().toEpochMilli());
        return stats;
    }

    /** 将 OID 列表拆成多包 GET 再合并，适配单 PDU 变量数受限的设备。 */
    private Map<String, String> snmpGetMergeBatches(Device d, List<String> oids) throws Exception {
        if (oids == null || oids.isEmpty()) return new HashMap<>();
        Map<String, String> merged = new HashMap<>();
        for (int i = 0; i < oids.size(); i += SNMP_GET_MAX_VARS_PER_PDU) {
            int end = Math.min(i + SNMP_GET_MAX_VARS_PER_PDU, oids.size());
            merged.putAll(snmpGet(d, oids.subList(i, end)));
        }
        return merged;
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

    /** 按 OID 列表顺序取第一个可解析为 0–100 的 CPU 占用率字符串。 */
    private static String pickFirstCpuPercent(Map<String, String> cpuResult, List<String> cpuOids) {
        if (cpuResult == null || cpuOids == null) return "-";
        for (String oid : cpuOids) {
            String raw = SnmpUtils.getSnmpMapValue(cpuResult, oid);
            String normalized = normalizePercentString(raw);
            if (normalized != null) return normalized;
        }
        return "-";
    }

    /**
     * WALK IF-MIB ifHCInOctets / ifHCOutOctets 子树，将各接口 Counter 相加，得到字节累计（供前端展示；速率需相邻两次采集差分）。
     */
    private String walkSumIfHcOctets(Device d, String baseOid) {
        if (d == null || baseOid == null || baseOid.isBlank()) return null;
        try {
            Map<String, String> walked;
            int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
            int max = Math.min(1024, Math.max(16, ifTrafficMaxRows));
            if (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) {
                SnmpV3Params v3 = objectMapper.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
                if (v3.getUsername() == null || v3.getUsername().isBlank()) return null;
                walked = SnmpUtils.snmpV3Walk(
                        d.getIp(), port, timeoutMs, retries,
                        v3.getUsername(),
                        v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                        v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                        baseOid, max);
            } else {
                String community = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()) ? d.getSnmpCommunity() : "public";
                walked = SnmpUtils.snmpV2cWalk(d.getIp(), port, community, timeoutMs, retries, baseOid, max);
            }
            if (walked == null || walked.isEmpty()) return null;
            if (isTrafficDebugTarget(d)) {
                logTrafficWalkSnapshot(d, baseOid, walked);
            }
            BigInteger sum = BigInteger.ZERO;
            boolean any = false;
            for (String raw : walked.values()) {
                BigInteger x = firstUnsignedBigInteger(raw);
                if (x != null && x.signum() >= 0) {
                    sum = sum.add(x);
                    any = true;
                }
            }
            return any ? sum.toString() : null;
        } catch (Exception e) {
            log.debug("设备 id={} ip={} WALK 汇总 {} 失败: {}", d.getId(), d.getIp(), baseOid, e.getMessage());
            return null;
        }
    }

    private boolean isTrafficDebugTarget(Device d) {
        if (d == null || d.getIp() == null || d.getIp().isBlank()) return false;
        String target = debugTrafficIp == null ? "" : debugTrafficIp.trim();
        return !target.isEmpty() && target.equals(d.getIp().trim());
    }

    /** 仅调试指定 IP：打印 WALK 原始条目数量与前若干项，便于定位是否拿到了接口计数器。 */
    private void logTrafficWalkSnapshot(Device d, String baseOid, Map<String, String> walked) {
        try {
            String sample = walked.entrySet().stream()
                    .limit(8)
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(" | "));
            log.info("SNMP WALK 调试 id={} ip={} baseOid={} rows={} sample={}",
                    d.getId(), d.getIp(), baseOid, walked.size(), sample);
        } catch (Exception e) {
            log.info("SNMP WALK 调试 id={} ip={} baseOid={} rows={}（sample 构建失败: {}）",
                    d.getId(), d.getIp(), baseOid, walked != null ? walked.size() : 0, e.getMessage());
        }
    }

    private static BigInteger firstUnsignedBigInteger(String raw) {
        if (raw == null) return null;
        Matcher m = NUMBER_PATTERN.matcher(raw);
        if (!m.find()) return null;
        String g = m.group(1);
        if (g.contains(".")) {
            g = g.substring(0, g.indexOf('.'));
        }
        if (g.isEmpty()) return null;
        try {
            return new BigInteger(g);
        } catch (NumberFormatException e) {
            return null;
        }
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

        String oidVendor = SnmpUtils.resolveVendorForOids(sysDescr, sysObjectId, d.getVendor());
        String typeVal = d.getType() != null ? d.getType().toValue() : null;
        List<String> cpuOids = SnmpUtils.getCpuOidsToTry(oidVendor, typeVal, sysObjectId);
        List<String> memOids = SnmpUtils.getMemoryOidsToTry(oidVendor, typeVal, sysObjectId);

        if ((d.getVendor() == null || d.getVendor().isBlank()) && oidVendor != null && !oidVendor.isBlank()) {
            try {
                String vendorLabel = SnmpUtils.vendorKeyToUiLabel(oidVendor);
                String savedVendor = (vendorLabel != null && !vendorLabel.isBlank()) ? vendorLabel : oidVendor;
                if (d.getId() != null) {
                    deviceRepository.updateVendorById(d.getId(), savedVendor);
                    d.setVendor(savedVendor);
                }
            } catch (Exception ex) {
                log.debug("保存 SNMP 自动识别厂商失败 id={}: {}", d.getId(), ex.getMessage());
            }
        }

        String cpu = "-";
        try {
            if (!cpuOids.isEmpty()) {
                Map<String, String> cpuResult = snmpGetMergeBatches(d, cpuOids);
                cpu = pickFirstCpuPercent(cpuResult, cpuOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} CPU OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }
        if ("-".equals(cpu)) {
            String walkedCpu = walkFirstPercentFromBase(d, "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5");
            if (walkedCpu != null) {
                cpu = walkedCpu;
                log.debug("设备 id={} ip={} 通过 SNMP WALK 获取 CPU={}%", d.getId(), ip, cpu);
            }
        }
        if ("-".equals(cpu)) {
            String walkedCpu = walkFirstPercentFromBase(d, "1.3.6.1.4.1.2011.10.2.4.1.3");
            if (walkedCpu != null) {
                cpu = walkedCpu;
                log.debug("设备 id={} ip={} 通过 SNMP WALK(hw10 CPU) 获取 CPU={}%", d.getId(), ip, cpu);
            }
        }

        String memory = "-";
        try {
            if (!memOids.isEmpty()) {
                Map<String, String> memResult = snmpGetMergeBatches(d, memOids);
                memory = parseMemoryPercent(memResult, memOids);
            }
        } catch (Exception e) {
            log.debug("设备 id={} ip={} 内存 OID 请求超时或失败: {}", d.getId(), ip, e.getMessage());
        }
        if ("-".equals(memory)) {
            String walkedMem = walkFirstPercentFromBase(d, "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7");
            if (walkedMem == null) {
                walkedMem = walkFirstPercentFromBase(d, "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.6");
            }
            if (walkedMem != null) {
                memory = walkedMem;
                log.debug("设备 id={} ip={} 通过 SNMP WALK 获取 memory={}%", d.getId(), ip, memory);
            }
        }
        if ("-".equals(memory)) {
            String walkedMem = walkFirstPercentFromBase(d, "1.3.6.1.4.1.2011.10.2.4.2");
            if (walkedMem != null) {
                memory = walkedMem;
                log.debug("设备 id={} ip={} 通过 SNMP WALK(hw10 mem) 获取 memory={}%", d.getId(), ip, memory);
            }
        }
        if ("-".equals(memory)) {
            String ciscoMem = walkCiscoMemoryPoolPercent(d);
            if (ciscoMem != null) {
                memory = ciscoMem;
                log.debug("设备 id={} ip={} 通过 SNMP WALK(ciscoMemoryPool) 获取 memory={}%", d.getId(), ip, memory);
            }
        }
        if ("-".equals(cpu) && "-".equals(memory)) {
            log.warn("设备 id={} ip={} SNMP 可达但未解析到 CPU/内存（resolvedVendor={}）。请核对设备型号 OID，当前 CPU OID={}，内存 OID={}",
                    d.getId(), ip, oidVendor, cpuOids, memOids);
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
                if (oidVendor != null && !oidVendor.isBlank()) {
                    redisTemplate.opsForHash().put(key, "snmpVendorResolved", oidVendor);
                }
                if (collectIfTraffic) {
                    String ifInHc = walkSumIfHcOctets(d, SnmpUtils.OID_WALK_IF_HC_IN_OCTETS);
                    String ifOutHc = walkSumIfHcOctets(d, SnmpUtils.OID_WALK_IF_HC_OUT_OCTETS);
                    // 兼容部分设备 ifHC* 恒为 0：当 ifHC 为空或为 0 时，再尝试 32 位 ifIn/ifOutOctets
                    String ifInLegacy = null;
                    String ifOutLegacy = null;
                    if (ifInHc == null || isZeroCounter(ifInHc)) ifInLegacy = walkSumIfHcOctets(d, SnmpUtils.OID_WALK_IF_IN_OCTETS);
                    if (ifOutHc == null || isZeroCounter(ifOutHc)) ifOutLegacy = walkSumIfHcOctets(d, SnmpUtils.OID_WALK_IF_OUT_OCTETS);
                    String ifIn = preferTrafficCounter(ifInHc, ifInLegacy);
                    String ifOut = preferTrafficCounter(ifOutHc, ifOutLegacy);
                    if (ifIn != null) redisTemplate.opsForHash().put(key, "ifInOctetsTotal", ifIn);
                    if (ifOut != null) redisTemplate.opsForHash().put(key, "ifOutOctetsTotal", ifOut);
                }
                if (useRedis) {
                    log.info("SNMP 采集成功 设备 id={} ip={} name={} | sysName={} ifNumber={} vendor={} cpu={} memory={} | 已写入 Redis key={}",
                            d.getId(), ip, d.getName(), sysName, ifNumber, oidVendor, cpu, memory, key);
                }
            } catch (Exception e) {
                log.error("设备 id={} ip={} 写入 Redis 失败: {}", d.getId(), ip, e.getMessage());
            }
        }
        if (fallbackSshWhenCpuMemMissing && "-".equals(cpu) && "-".equals(memory)) {
            log.info("SNMP 未解析到 CPU/内存，尝试 SSH/Telnet 补采 id={} ip={}", d.getId(), ip);
            tryFallbackToSsh(d, false);
        }
    }

    /** 从采集结果解析内存使用率：先取 0–100 的单项值（如华为），再尝试 Cisco used/(used+free)、HR used/size */
    private static String parseMemoryPercent(Map<String, String> result, List<String> memOids) {
        for (String oid : memOids) {
            String v = SnmpUtils.getSnmpMapValue(result, oid);
            String normalized = normalizePercentString(v);
            if (normalized != null) return normalized;
        }
        String ciscoPoolPct = parseCiscoMemoryPoolPercent(result);
        if (ciscoPoolPct != null) return ciscoPoolPct;
        String ciscoUsedStr = SnmpUtils.getSnmpMapValue(result, SnmpUtils.OID_MEM_CISCO_USED);
        String ciscoFreeStr = SnmpUtils.getSnmpMapValue(result, SnmpUtils.OID_MEM_CISCO_FREE);
        Double ciscoUsed = parseNumber(ciscoUsedStr);
        Double ciscoFree = parseNumber(ciscoFreeStr);
        if (ciscoUsed != null && ciscoFree != null) {
            long used = Math.round(ciscoUsed);
            long free = Math.round(ciscoFree);
            long total = used + free;
            if (total > 0) {
                int pct = (int) Math.round(used * 100.0 / total);
                return String.valueOf(Math.min(100, Math.max(0, pct)));
            }
        }
        String usedStr = SnmpUtils.getSnmpMapValue(result, SnmpUtils.OID_MEM_HR_USED);
        String sizeStr = SnmpUtils.getSnmpMapValue(result, SnmpUtils.OID_MEM_HR_SIZE);
        Double hrUsed = parseNumber(usedStr);
        Double hrSize = parseNumber(sizeStr);
        if (hrUsed != null && hrSize != null) {
            long used = Math.round(hrUsed);
            long size = Math.round(hrSize);
            if (size > 0) {
                int pct = (int) Math.round(used * 100.0 / size);
                return String.valueOf(Math.min(100, Math.max(0, pct)));
            }
        }
        return "-";
    }

    /**
     * Cisco 内存池（ciscoMemoryPool）可能不在固定索引 .1，且常见为多池。
     * 这里按 OID 后缀索引聚合 used/free，再以总 used/(used+free) 计算百分比。
     */
    private static String parseCiscoMemoryPoolPercent(Map<String, String> result) {
        if (result == null || result.isEmpty()) return null;
        final String usedBase = "1.3.6.1.4.1.9.9.48.1.1.1.5.";
        final String freeBase = "1.3.6.1.4.1.9.9.48.1.1.1.6.";
        Map<String, Double> usedByIdx = new HashMap<>();
        Map<String, Double> freeByIdx = new HashMap<>();
        for (Map.Entry<String, String> e : result.entrySet()) {
            if (e.getKey() == null) continue;
            String key = normalizeOidKey(e.getKey());
            if (key.startsWith(usedBase)) {
                String idx = key.substring(usedBase.length());
                Double n = parseNumber(e.getValue());
                if (n != null) usedByIdx.put(idx, n);
                continue;
            }
            if (key.startsWith(freeBase)) {
                String idx = key.substring(freeBase.length());
                Double n = parseNumber(e.getValue());
                if (n != null) freeByIdx.put(idx, n);
            }
        }
        if (usedByIdx.isEmpty() || freeByIdx.isEmpty()) return null;
        double usedSum = 0D;
        double freeSum = 0D;
        for (Map.Entry<String, Double> e : usedByIdx.entrySet()) {
            Double free = freeByIdx.get(e.getKey());
            if (free == null) continue;
            usedSum += e.getValue();
            freeSum += free;
        }
        double total = usedSum + freeSum;
        if (total <= 0D) return null;
        int pct = (int) Math.round(usedSum * 100.0 / total);
        return String.valueOf(Math.min(100, Math.max(0, pct)));
    }

    private static String normalizePercentString(String raw) {
        Double n = parseNumber(raw);
        if (n == null) return null;
        if (n < 0 || n > 100) return null;
        return String.valueOf((int) Math.round(n));
    }

    private static Double parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        Matcher m = NUMBER_PATTERN.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeOidKey(String oid) {
        if (oid == null || oid.isBlank()) return "";
        return oid.startsWith(".") ? oid.substring(1) : oid;
    }

    private String walkFirstPercentFromBase(Device d, String baseOid) {
        if (d == null || d.getIp() == null || d.getIp().isBlank() || baseOid == null || baseOid.isBlank()) return null;
        try {
            Map<String, String> walked;
            int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
            if (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) {
                SnmpV3Params v3 = objectMapper.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
                if (v3.getUsername() == null || v3.getUsername().isBlank()) return null;
                walked = SnmpUtils.snmpV3Walk(
                        d.getIp(), port, timeoutMs, retries,
                        v3.getUsername(),
                        v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                        v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                        baseOid, SNMP_WALK_PERCENT_MAX_ROWS
                );
            } else {
                String community = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()) ? d.getSnmpCommunity() : "public";
                walked = SnmpUtils.snmpV2cWalk(d.getIp(), port, community, timeoutMs, retries, baseOid, SNMP_WALK_PERCENT_MAX_ROWS);
            }
            if (walked == null || walked.isEmpty()) return null;
            Integer best = null;
            Integer first = null;
            for (String v : walked.values()) {
                String normalized = normalizePercentString(v);
                if (normalized == null) continue;
                int n = Integer.parseInt(normalized);
                if (first == null) first = n;
                // 优先选择非 0 的最大值，避免子树前几个节点为 0 导致看起来“无数据”
                if (n > 0 && (best == null || n > best)) best = n;
            }
            if (best != null) return String.valueOf(best);
            if (first != null) return String.valueOf(first);
            return null;
        } catch (Exception e) {
            log.debug("设备 id={} ip={} SNMP WALK {} 失败: {}", d.getId(), d.getIp(), baseOid, e.getMessage());
            return null;
        }
    }

    /**
     * Cisco ciscoMemoryPool 兜底：WALK used/free 全部索引并汇总为 used/(used+free) 百分比。
     * 适配内存池索引不固定（不一定是 .1）以及多内存池设备。
     */
    private String walkCiscoMemoryPoolPercent(Device d) {
        if (d == null || d.getIp() == null || d.getIp().isBlank()) return null;
        final String usedBase = "1.3.6.1.4.1.9.9.48.1.1.1.5";
        final String freeBase = "1.3.6.1.4.1.9.9.48.1.1.1.6";
        try {
            Map<String, String> usedWalk;
            Map<String, String> freeWalk;
            int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
            int maxRows = 128;
            if (d.getSnmpVersion() == Device.SnmpVersion.v3 && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank()) {
                SnmpV3Params v3 = objectMapper.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
                if (v3.getUsername() == null || v3.getUsername().isBlank()) return null;
                usedWalk = SnmpUtils.snmpV3Walk(d.getIp(), port, timeoutMs, retries, v3.getUsername(),
                        v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                        v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                        usedBase, maxRows);
                freeWalk = SnmpUtils.snmpV3Walk(d.getIp(), port, timeoutMs, retries, v3.getUsername(),
                        v3.getAuthPassword() != null ? v3.getAuthPassword() : "",
                        v3.getPrivPassword() != null ? v3.getPrivPassword() : "",
                        freeBase, maxRows);
            } else {
                String community = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank()) ? d.getSnmpCommunity() : "public";
                usedWalk = SnmpUtils.snmpV2cWalk(d.getIp(), port, community, timeoutMs, retries, usedBase, maxRows);
                freeWalk = SnmpUtils.snmpV2cWalk(d.getIp(), port, community, timeoutMs, retries, freeBase, maxRows);
            }
            if (usedWalk == null || usedWalk.isEmpty() || freeWalk == null || freeWalk.isEmpty()) return null;

            Map<String, BigInteger> usedByIdx = new HashMap<>();
            for (Map.Entry<String, String> e : usedWalk.entrySet()) {
                String idx = extractSuffixIndex(e.getKey(), usedBase);
                BigInteger val = firstUnsignedBigInteger(e.getValue());
                if (idx != null && val != null && val.signum() >= 0) usedByIdx.put(idx, val);
            }
            if (usedByIdx.isEmpty()) return null;

            BigInteger usedSum = BigInteger.ZERO;
            BigInteger freeSum = BigInteger.ZERO;
            for (Map.Entry<String, String> e : freeWalk.entrySet()) {
                String idx = extractSuffixIndex(e.getKey(), freeBase);
                if (idx == null) continue;
                BigInteger used = usedByIdx.get(idx);
                BigInteger free = firstUnsignedBigInteger(e.getValue());
                if (used == null || free == null || free.signum() < 0) continue;
                usedSum = usedSum.add(used);
                freeSum = freeSum.add(free);
            }
            BigInteger total = usedSum.add(freeSum);
            if (total.signum() <= 0) return null;
            int pct = usedSum.multiply(BigInteger.valueOf(100)).add(total.divide(BigInteger.valueOf(2))).divide(total).intValue();
            pct = Math.max(0, Math.min(100, pct));
            return String.valueOf(pct);
        } catch (Exception e) {
            log.debug("设备 id={} ip={} SNMP WALK ciscoMemoryPool 失败: {}", d.getId(), d.getIp(), e.getMessage());
            return null;
        }
    }

    private static String extractSuffixIndex(String fullOid, String baseOid) {
        if (fullOid == null || baseOid == null) return null;
        String f = normalizeOidKey(fullOid);
        String b = normalizeOidKey(baseOid);
        if (!f.startsWith(b)) return null;
        if (f.length() == b.length()) return "";
        if (f.charAt(b.length()) != '.') return null;
        return f.substring(b.length() + 1);
    }

    private static boolean isZeroCounter(String raw) {
        BigInteger v = firstUnsignedBigInteger(raw);
        return v != null && v.signum() == 0;
    }

    /** 选择更可信的总流量计数：优先非 0；都非 0 时取较大值。 */
    private static String preferTrafficCounter(String primary, String fallback) {
        BigInteger p = firstUnsignedBigInteger(primary);
        BigInteger f = firstUnsignedBigInteger(fallback);
        if (p == null) return f != null ? f.toString() : null;
        if (f == null) return p.toString();
        if (p.signum() == 0 && f.signum() > 0) return f.toString();
        if (f.signum() == 0 && p.signum() > 0) return p.toString();
        return p.max(f).toString();
    }
}
