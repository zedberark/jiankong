package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.Device.DeviceStatus;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络设备 SSH/Telnet 采集：按设备端口选协议（22=SSH、23=Telnet），执行厂商自查命令与厂商 CPU/内存命令，
 * 解析结果写入内存缓存并追加写入 Redis（key netpulse:snmp:device:ip:{ip}），供 GET /metrics/realtime 返回前端。厂商识别仅当设备 vendor 为空时回写设备表。
 * 需 ssh-collect.enabled=true 且设备配置用户名密码。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "ssh-collect.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DeviceSshCollectService {

    /** 与 SnmpStatsService 读取格式一致，供实时指标接口从 Redis 按 IP 读取 */
    private static final String REDIS_KEY_PREFIX_IP = "netpulse:snmp:device:ip:";
    private static final String REDIS_KEY_LAST = "lastCollectTime";

    /** Web SSH 连不上的设备在此时间内不参与定时/一键采集（避免重复尝试），单位毫秒 */
    private static final long WEB_SSH_UNREACHABLE_SKIP_MS = 30 * 60 * 1000L;

    /** 记录 Web SSH 连接失败时的设备 ID 与时间，采集时跳过 */
    private final Map<Long, Long> webSshUnreachableSince = new ConcurrentHashMap<>();

    /** SSH/Telnet 采集结果缓存（按 IP），供实时指标接口返回前端；同时写入 Redis */
    private final Map<String, DeviceStatsService.DeviceStats> sshStatsCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("snmpStringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /** Web SSH 连接失败时由 SshWebSocketHandler 调用，此后一段时间内不对该设备做 SSH 采集 */
    public void markWebSshUnreachable(Long deviceId) {
        if (deviceId != null) webSshUnreachableSince.put(deviceId, System.currentTimeMillis());
    }

    /** Web SSH 连接成功时调用，清除“连不上”标记，后续可继续采集 */
    public void markWebSshReachable(Long deviceId) {
        if (deviceId != null) webSshUnreachableSince.remove(deviceId);
    }

    /** 是否因 Web SSH 连不上而处于“跳过采集”状态（供 SNMP 回退 SSH 时判断） */
    public boolean isWebSshUnreachable(Long deviceId) {
        if (deviceId == null) return false;
        Long since = webSshUnreachableSince.get(deviceId);
        if (since == null) return false;
        if (System.currentTimeMillis() - since > WEB_SSH_UNREACHABLE_SKIP_MS) {
            webSshUnreachableSince.remove(deviceId);
            return false;
        }
        return true;
    }

    private static boolean isSupportedVendor(String vendorLower) {
        return vendorLower != null && SUPPORTED_VENDORS.contains(vendorLower.trim().toLowerCase(Locale.ROOT));
    }

    private void markUnsupportedVendor(Long deviceId) {
        if (deviceId != null) unsupportedVendorSkipSince.put(deviceId, System.currentTimeMillis());
    }

    private boolean isUnsupportedVendorSkipped(Long deviceId) {
        return deviceId != null && unsupportedVendorSkipSince.containsKey(deviceId);
    }

    /** 华为旧版 / 部分交换机 */
    private static final Pattern HUAWEI_CPU_LEGACY = Pattern.compile(
            "(?:CPU Usage|CPU utilization for ten seconds)\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    /** 华为常见：CPU utilization for five seconds: 28%: one minute: …（VRP5/8 常见） */
    private static final Pattern HUAWEI_CPU_FIVE_SEC = Pattern.compile(
            "CPU utilization for five seconds\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    /** 华为：CPU Usage            : 28% Max: … */
    private static final Pattern HUAWEI_CPU_USAGE_LINE = Pattern.compile(
            "CPU\\s+Usage\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    /**
     * 表格：Index / busy cycle / five seconds / one minute / five minutes<br>
     * 例：0       7%       6%              7%               6%
     */
    private static final Pattern HUAWEI_CPU_TABLE_ROW = Pattern.compile(
            "(?m)^\\s*(\\d+)\\s+([\\d.]+)%\\s+([\\d.]+)%\\s+([\\d.]+)%\\s+([\\d.]+)%\\s*$");

    private static final Pattern HUAWEI_MEM = Pattern.compile(
            "Memory Using Percentage Is\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    /** 诊断视图等：Used Ratio For Memory : 95% */
    private static final Pattern HUAWEI_MEM_RATIO = Pattern.compile(
            "Used Ratio For Memory\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    /** 无 Is 的变体 */
    private static final Pattern HUAWEI_MEM_PCT = Pattern.compile(
            "Memory Using Percentage\\s*:\\s*([\\d.]+)\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Pattern CISCO_CPU = Pattern.compile("CPU utilization for five seconds\\s*:\\s*(\\d+)%");
    /** Cisco show memory: Head Total(b) Used(b) Free(b) - 取 Processor 行 Used/Total */
    private static final Pattern CISCO_MEM = Pattern.compile("Processor\\s+\\S+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
    /** 锐捷 show cpu：CPU utilization in five seconds: 2.80% */
    private static final Pattern RUIJIE_CPU = Pattern.compile("CPU utilization in five seconds\\s*:\\s*([\\d.]+)\\s*%");
    /** 锐捷 show memory：74.9% used rate */
    private static final Pattern RUIJIE_MEM = Pattern.compile("([\\d.]+)\\s*%\\s*used rate");
    private static final Pattern GENERIC_PCT = Pattern.compile("([\\d.]+)\\s*%");

    /** 仅采集并回写设备表的厂商：思科、华为、锐捷、华三；其他厂商不写入设备表且后续跳过采集 */
    private static final Set<String> SUPPORTED_VENDORS = Set.of("cisco", "huawei", "ruijie", "h3c");

    /** 自查命令：先试华为/H3C/锐捷，再试思科 */
    private static final String[] VERSION_COMMANDS = new String[] { "display version", "show version" };
    /** 厂商关键字（按优先级），解析到则设 vendor */
    private static final String[] VENDOR_KEYWORDS = new String[] { "Huawei", "H3C", "Comware", "Cisco", "Ruijie", "Juniper" };
    /** 未识别厂商时的 CPU 通用探测命令（按常见设备优先级）。 */
    private static final String[] CPU_FALLBACK_COMMANDS = new String[] {
            "display cpu-usage",
            "dis cpu-usage",
            "show processes cpu",
            "show cpu",
            "display cpu"
    };
    /** 未识别厂商时的内存通用探测命令（按常见设备优先级）。 */
    private static final String[] MEMORY_FALLBACK_COMMANDS = new String[] {
            "display memory-usage",
            "dis memory-usage",
            "show memory",
            "display memory",
            "show processes memory"
    };

    /** 采集到非支持厂商（非思科/华为/锐捷/华三）的设备 ID，后续采集跳过且不写入设备表 */
    private final Map<Long, Long> unsupportedVendorSkipSince = new ConcurrentHashMap<>();

    private final DeviceRepository deviceRepository;
    private final BatchCommandService batchCommandService;
    private final WebSshService webSshService;

    /** 立即采集所有符合条件的设备（供「刷新」/一键采集调用），返回成功采集的设备数 */
    public int collectAllNow() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        int ok = 0;
        for (Device d : devices) {
            if (d.getType() == Device.DeviceType.server) {
                log.debug("SSH/Telnet 采集跳过 id={} ip={} 原因：类型为服务器", d.getId(), d.getIp());
                continue;
            }
            if (d.getStatus() == DeviceStatus.offline) {
                log.debug("SSH/Telnet 采集跳过 id={} ip={} 原因：设备离线", d.getId(), d.getIp());
                continue;
            }
            if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) {
                log.debug("SSH/Telnet 采集跳过 id={} ip={} 原因：未配置 SSH/Telnet 用户名或密码", d.getId(), d.getIp());
                continue;
            }
            if (d.getIp() == null || d.getIp().isBlank()) {
                log.debug("SSH/Telnet 采集跳过 id={} ip= 原因：IP 为空", d.getId());
                continue;
            }
            if (isWebSshUnreachable(d.getId())) {
                log.debug("SSH/Telnet 采集跳过 id={} ip={} 原因：Web SSH 曾连不上，暂不采集", d.getId(), d.getIp());
                continue;
            }
            if (isUnsupportedVendorSkipped(d.getId())) {
                log.debug("SSH/Telnet 采集跳过 id={} ip={} 原因：厂商非思科/华为/锐捷/华三，已跳过", d.getId(), d.getIp());
                continue;
            }
            try {
                collectOneAndSave(d);
                ok++;
            } catch (Exception e) {
                log.warn("SSH 采集异常 id={} ip={}: {}", d.getId(), d.getIp(), e.getMessage());
            }
        }
        return ok;
    }

    /** 单设备立即采集（供接口「立即采集」调用），成功返回 true */
    public boolean collectOne(Long deviceId) {
        Device d = deviceRepository.findById(deviceId).filter(x -> !Boolean.TRUE.equals(x.getDeleted())).orElse(null);
        if (d == null) return false;
        if (d.getStatus() == DeviceStatus.offline) return false;
        if (d.getType() == Device.DeviceType.server) return false;
        if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) return false;
        if (d.getIp() == null || d.getIp().isBlank()) return false;
        if (isWebSshUnreachable(deviceId)) {
            log.debug("SSH 单设备采集跳过 id={} 原因：Web SSH 曾连不上", deviceId);
            return false;
        }
        if (isUnsupportedVendorSkipped(deviceId)) {
            log.debug("SSH 单设备采集跳过 id={} 原因：厂商非思科/华为/锐捷/华三", deviceId);
            return false;
        }
        try {
            collectOneAndSave(d);
            return true;
        } catch (Exception e) {
            log.warn("SSH 单设备采集失败 id={}: {}", deviceId, e.getMessage());
            return false;
        }
    }

    @Scheduled(initialDelayString = "${ssh-collect.initial-delay-ms:30000}", fixedDelayString = "${ssh-collect.interval-ms:300000}")
    public void collectAllAndSaveToRedis() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        for (Device d : devices) {
            if (d.getType() == Device.DeviceType.server) {
                log.debug("SSH/Telnet 定时采集跳过 id={} ip={} 原因：类型为服务器", d.getId(), d.getIp());
                continue;
            }
            if (d.getStatus() == DeviceStatus.offline) {
                log.debug("SSH/Telnet 定时采集跳过 id={} ip={} 原因：设备离线", d.getId(), d.getIp());
                continue;
            }
            if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) {
                log.debug("SSH/Telnet 定时采集跳过 id={} ip={} 原因：未配置 SSH/Telnet 用户名或密码", d.getId(), d.getIp());
                continue;
            }
            String ip = d.getIp();
            if (ip == null || ip.isBlank()) {
                log.debug("SSH/Telnet 定时采集跳过 id={} 原因：IP 为空", d.getId());
                continue;
            }
            if (isWebSshUnreachable(d.getId())) {
                log.debug("SSH/Telnet 定时采集跳过 id={} ip={} 原因：Web SSH 曾连不上，暂不采集", d.getId(), ip);
                continue;
            }
            if (isUnsupportedVendorSkipped(d.getId())) {
                log.debug("SSH/Telnet 定时采集跳过 id={} ip={} 原因：厂商非思科/华为/锐捷/华三", d.getId(), ip);
                continue;
            }
            try {
                collectOneAndSave(d);
            } catch (Exception e) {
                log.warn("SSH 采集异常 id={} ip={} name={}: {}", d.getId(), ip, d.getName(), e.getMessage());
            }
        }
    }

    /** 每 5 分钟检查一次：曾被标记为 Web SSH 连不上的设备是否已恢复可连，可连则清除标记以便后续采集 */
    @Scheduled(initialDelayString = "${ssh-collect.webssh-recheck-initial-delay-ms:60000}", fixedRateString = "${ssh-collect.webssh-recheck-interval-ms:300000}")
    public void recheckWebSshReachable() {
        if (webSshUnreachableSince.isEmpty()) return;
        Set<Long> ids = Set.copyOf(webSshUnreachableSince.keySet());
        OutputStream discard = new OutputStream() {
            @Override
            public void write(int b) {}
            @Override
            public void write(byte[] b, int off, int len) {}
        };
        for (Long deviceId : ids) {
            if (!isWebSshUnreachable(deviceId)) continue;
            Device d = deviceRepository.findById(deviceId).filter(x -> !Boolean.TRUE.equals(x.getDeleted())).orElse(null);
            if (d == null || d.getType() == Device.DeviceType.server) continue;
            if (d.getStatus() == DeviceStatus.offline) continue;
            if (d.getSshUser() == null || d.getSshUser().isBlank() || d.getSshPassword() == null) continue;
            if (d.getIp() == null || d.getIp().isBlank()) continue;
            try {
                Optional<WebSshService.ShellSessionHolder> holderOpt = webSshService.createSession(deviceId, discard);
                if (holderOpt.isPresent()) {
                    webSshService.disconnect(holderOpt.get());
                    markWebSshReachable(deviceId);
                    log.info("Web SSH 探活成功 id={} ip={}，已恢复采集", deviceId, d.getIp());
                }
            } catch (Exception e) {
                log.trace("Web SSH 探活仍不可达 id={} ip={}: {}", deviceId, d.getIp(), e.getMessage());
            }
        }
    }

    private void collectOneAndSave(Device d) {
        String ip = d.getIp().trim();

        String vendorForCmd = resolveVendorForCmd(d);
        if (vendorForCmd.isBlank()) {
            log.info("厂商未识别，启用通用命令探测 id={} ip={}", d.getId(), ip);
        }
        String vendorLog = vendorForCmd.isBlank() ? "unknown" : vendorForCmd;
        log.info("开始 SSH/Telnet 采集：id={} ip={} name={} vendor={}（采 CPU/内存，写缓存）", d.getId(), ip, d.getName(), vendorLog);

        String cpu = "-";
        String memory = "-";
        for (String cmd : getCpuCommands(vendorForCmd)) {
            String out = batchCommandService.runCommand(d, cmd);
            cpu = parseCpu(out, vendorForCmd);
            if (!"-".equals(cpu)) break;
        }
        for (String cmd : getMemoryCommands(vendorForCmd)) {
            String out = batchCommandService.runCommand(d, cmd);
            memory = parseMemory(out, vendorForCmd);
            if (!"-".equals(memory)) break;
        }
        if ("-".equals(cpu) && "-".equals(memory)) {
            log.warn("SSH 采集解析失败：CPU/内存命令输出未匹配到数值 id={} ip={} vendor={}，请核对厂商命令或正则", d.getId(), ip, vendorLog);
        }

        // 写入内存缓存并追加写入 Redis
        long now = Instant.now().toEpochMilli();
        DeviceStatsService.DeviceStats stats = new DeviceStatsService.DeviceStats();
        Double cpuNum = "-".equals(cpu) ? null : parsePercent(cpu);
        Double memNum = "-".equals(memory) ? null : parsePercent(memory);
        stats.setCpuPercent(cpuNum);
        stats.setMemoryPercent(memNum);
        stats.setUpdatedAt(now);
        sshStatsCache.put(ip, stats);
        saveStatsToRedis(ip, d.getName(), stats);
        log.info("SSH 采集已写缓存与 Redis id={} ip={} cpu={}% memory={}%", d.getId(), ip, cpu, memory);
    }

    private static Double parsePercent(String v) {
        if (v == null || v.isEmpty() || "-".equals(v.trim())) return null;
        try {
            double d = Double.parseDouble(v.trim());
            if (d >= 0 && d <= 100) return Double.valueOf(d);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /** 按 IP 取 SSH/Telnet 采集缓存，供实时指标接口直接返回前端 */
    public DeviceStatsService.DeviceStats getStatsByIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        return sshStatsCache.get(ip.trim());
    }

    /** 所有已采集设备的缓存快照（IP → 统计），供实时指标接口合并用 */
    public Map<String, DeviceStatsService.DeviceStats> getStatsSnapshotByIp() {
        return new HashMap<>(sshStatsCache);
    }

    /**
     * 从 Web SSH 终端内容解析并写入缓存：用户在终端里执行 show version / show cpu / show memory 后，
     * 点击「写入设备指标」提交当前终端输出，本方法从整段文本中解析厂商、CPU、内存并写入缓存，供设备指标页展示。
     */
    public boolean ingestFromWebSshOutput(Long deviceId, String terminalOutput) {
        if (deviceId == null || terminalOutput == null || terminalOutput.isBlank()) return false;
        Device d = deviceRepository.findById(deviceId).filter(x -> !Boolean.TRUE.equals(x.getDeleted())).orElse(null);
        if (d == null || d.getIp() == null || d.getIp().isBlank()) return false;
        String ip = d.getIp().trim();
        String detectedVendor = parseVendor(terminalOutput);
        if (detectedVendor != null && (d.getVendor() == null || d.getVendor().isBlank())) {
            String model = parseModel(terminalOutput);
            if (d.getId() != null) {
                if (model != null && !model.isBlank()) {
                    deviceRepository.updateVendorAndModelById(d.getId(), detectedVendor, model);
                } else {
                    deviceRepository.updateVendorById(d.getId(), detectedVendor);
                }
            }
            d.setVendor(detectedVendor);
            if (model != null && !model.isBlank()) d.setModel(model);
            log.info("Web SSH 写入：已用终端输出回写设备厂商 id={} ip={} vendor={}", d.getId(), ip, detectedVendor);
        }
        String vendorForCmd = (detectedVendor != null ? detectedVendor : (d.getVendor() != null ? d.getVendor() : "")).toLowerCase(Locale.ROOT);
        String cpu = parseCpu(terminalOutput, vendorForCmd);
        String memory = parseMemory(terminalOutput, vendorForCmd);
        long now = Instant.now().toEpochMilli();
        DeviceStatsService.DeviceStats stats = new DeviceStatsService.DeviceStats();
        stats.setCpuPercent(parsePercent(cpu));
        stats.setMemoryPercent(parsePercent(memory));
        stats.setUpdatedAt(now);
        sshStatsCache.put(ip, stats);
        saveStatsToRedis(ip, d.getName(), stats);
        log.info("Web SSH 写入设备指标 id={} ip={} cpu={}% memory={}（终端输出已解析并写缓存与 Redis）", d.getId(), ip, cpu, memory);
        return true;
    }

    /** 将当前统计写入 Redis（key 与 SnmpStatsService 按 IP 读取格式一致），Redis 未配置时跳过 */
    private void saveStatsToRedis(String ip, String deviceName, DeviceStatsService.DeviceStats stats) {
        if (redisTemplate == null || ip == null || ip.isBlank()) return;
        String key = REDIS_KEY_PREFIX_IP + ip.trim();
        try {
            String cpuStr = stats.getCpuPercent() != null ? String.valueOf(stats.getCpuPercent()) : "";
            String memStr = stats.getMemoryPercent() != null ? String.valueOf(stats.getMemoryPercent()) : "";
            String lastStr = String.valueOf(stats.getUpdatedAt() > 0 ? stats.getUpdatedAt() : Instant.now().toEpochMilli());
            redisTemplate.opsForHash().put(key, "ip", ip.trim());
            redisTemplate.opsForHash().put(key, "cpu", cpuStr);
            redisTemplate.opsForHash().put(key, "memory", memStr);
            redisTemplate.opsForHash().put(key, REDIS_KEY_LAST, lastStr);
            redisTemplate.opsForHash().put(key, "name", deviceName != null && !deviceName.isBlank() ? deviceName : "-");
        } catch (Exception e) {
            log.warn("SSH 采集写 Redis 失败 ip={}: {}", ip, e.getMessage());
        }
    }

    /**
     * 解析用于选择 CPU/内存 命令的厂商标识（小写）。
     * 仅思科/华为/锐捷/华三会回写设备表并继续采集；其他厂商不写入设备表且后续跳过采集。
     */
    private String resolveVendorForCmd(Device d) {
        if (d.getVendor() != null && !d.getVendor().isBlank()) {
            String v = d.getVendor().trim().toLowerCase(Locale.ROOT);
            if (!isSupportedVendor(v)) {
                markUnsupportedVendor(d.getId());
                log.info("设备 id={} ip={} 厂商 {} 非思科/华为/锐捷/华三，后续跳过采集且不写入设备表", d.getId(), d.getIp(), d.getVendor());
                return "";
            }
            return v;
        }
        String versionOut = runVersionCommand(d);
        if (versionOut == null || versionOut.isBlank()) {
            return "";
        }
        String detectedVendor = parseVendor(versionOut);
        String detectedModel = parseModel(versionOut);
        log.info("厂商自查结果 id={} ip={} 识别到 vendor={} model={}", d.getId(), d.getIp(), detectedVendor, detectedModel);
        if (detectedVendor != null && !detectedVendor.isBlank()) {
            String vLower = detectedVendor.trim().toLowerCase(Locale.ROOT);
            if (!isSupportedVendor(vLower)) {
                markUnsupportedVendor(d.getId());
                log.info("设备 id={} ip={} 采集到厂商 {} 非思科/华为/锐捷/华三，不写入设备表，后续跳过采集", d.getId(), d.getIp(), detectedVendor);
                return "";
            }
            d.setVendor(detectedVendor);
            if (detectedModel != null && !detectedModel.isBlank()) {
                d.setModel(detectedModel);
            }
            if (d.getId() != null) {
                if (detectedModel != null && !detectedModel.isBlank()) {
                    deviceRepository.updateVendorAndModelById(d.getId(), detectedVendor, detectedModel);
                } else {
                    deviceRepository.updateVendorById(d.getId(), detectedVendor);
                }
            }
            log.info("已用自查结果回写设备厂商 id={} ip={} vendor={} model={}", d.getId(), d.getIp(), detectedVendor, detectedModel);
            return vLower;
        }
        return "";
    }

    /** 依次尝试 display version / show version，返回第一条有内容的输出 */
    private String runVersionCommand(Device d) {
        for (String cmd : VERSION_COMMANDS) {
            String out = batchCommandService.runCommand(d, cmd);
            if (out != null && !out.isBlank()) return out;
        }
        return null;
    }

    /** 从版本输出中解析厂商（Huawei/H3C/Cisco/Ruijie 等），用于回写设备表及后续选 CPU/内存命令。锐捷典型为 System description : Ruijie (X86 TESTBENCH) by Ruijie Networks. */
    private static String parseVendor(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) return null;
        String lower = versionOutput.toLowerCase(Locale.ROOT);
        if (lower.contains("huawei")) return "Huawei";
        if (lower.contains("h3c") || lower.contains("comware")) return "H3C";
        if (lower.contains("cisco")) return "Cisco";
        if (lower.contains("ruijie")) return "Ruijie";
        if (lower.contains("juniper")) return "Juniper";
        return null;
    }

    /** 从版本输出中解析型号（简要），可选回写设备表 */
    private static String parseModel(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) return null;
        // 华为/H3C：VRP (R) software, Version x.x
        Pattern vrp = Pattern.compile("VRP\\s*\\(R\\)\\s*software[^\\n]*Version\\s+([^\\s\\n,]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = vrp.matcher(versionOutput);
        if (m.find()) return "VRP " + m.group(1).trim();
        // 思科：Cisco IOS Software, ... Version x.x
        Pattern ios = Pattern.compile("(?:Cisco\\s+)?IOS\\s+Software[^\\n]*Version\\s+([^\\s\\n,]+)", Pattern.CASE_INSENSITIVE);
        m = ios.matcher(versionOutput);
        if (m.find()) return "IOS " + m.group(1).trim();
        // 锐捷：System software version : X86_RGOS 12.5(5)
        Pattern ruijie = Pattern.compile("(?:System\\s+)?software\\s+version\\s*:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
        m = ruijie.matcher(versionOutput);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private static String getCpuCommand(String vendor) {
        if (vendor.contains("huawei") || vendor.contains("h3c")) return "display cpu-usage";
        if (vendor.contains("cisco")) return "show processes cpu";
        if (vendor.contains("ruijie")) return "show cpu";
        return "display cpu-usage";
    }

    private static String getMemoryCommand(String vendor) {
        if (vendor.contains("huawei") || vendor.contains("h3c")) return "display memory-usage";
        if (vendor.contains("cisco")) return "show memory";
        if (vendor.contains("ruijie")) return "show memory";
        return "display memory-usage";
    }

    /** 厂商已识别时用单命令，未知厂商时按通用命令列表多轮探测。 */
    private static List<String> getCpuCommands(String vendor) {
        if (vendor != null && !vendor.isBlank()) {
            if (vendor.contains("huawei") || vendor.contains("h3c")) {
                return List.of("display cpu-usage", "dis cpu-usage", "display cpu");
            }
            if (vendor.contains("cisco")) return List.of("show processes cpu", "show cpu");
            if (vendor.contains("ruijie")) return List.of("show cpu");
            return List.of(getCpuCommand(vendor));
        }
        return List.of(CPU_FALLBACK_COMMANDS);
    }

    /** 厂商已识别时用单命令，未知厂商时按通用命令列表多轮探测。 */
    private static List<String> getMemoryCommands(String vendor) {
        if (vendor != null && !vendor.isBlank()) {
            if (vendor.contains("huawei") || vendor.contains("h3c")) {
                return List.of("display memory-usage", "dis memory-usage", "display memory");
            }
            if (vendor.contains("cisco")) return List.of("show memory", "show processes memory");
            if (vendor.contains("ruijie")) return List.of("show memory");
            return List.of(getMemoryCommand(vendor));
        }
        return List.of(MEMORY_FALLBACK_COMMANDS);
    }

    private static String parseCpu(String output, String vendor) {
        if (output == null || output.isBlank()) return "-";
        if (vendor.contains("huawei") || vendor.contains("h3c")) {
            String v = parseHuaweiH3cCpu(output);
            if (!"-".equals(v)) return v;
        }
        if (vendor.contains("cisco")) {
            Matcher m = CISCO_CPU.matcher(output);
            if (m.find()) return m.group(1).trim();
        }
        if (vendor.contains("ruijie")) {
            Matcher m = RUIJIE_CPU.matcher(output);
            if (m.find()) return m.group(1).trim();
        }
        Matcher m = GENERIC_PCT.matcher(output);
        if (m.find()) return m.group(1).trim();
        return "-";
    }

    /**
     * 华为/H3C {@code display cpu-usage} 各版本差异大，按常见格式依次尝试。
     */
    private static String parseHuaweiH3cCpu(String output) {
        Matcher m = HUAWEI_CPU_FIVE_SEC.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = HUAWEI_CPU_USAGE_LINE.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = HUAWEI_CPU_LEGACY.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = HUAWEI_CPU_TABLE_ROW.matcher(output);
        if (m.find()) {
            // 列：Index、busy cycle、five seconds、one minute、five minutes → 取第 3 个百分比
            return m.group(3).trim();
        }
        return "-";
    }

    private static String parseMemory(String output, String vendor) {
        if (output == null || output.isBlank()) return "-";
        if (vendor.contains("huawei") || vendor.contains("h3c")) {
            String v = parseHuaweiH3cMemory(output);
            if (!"-".equals(v)) return v;
        }
        if (vendor.contains("cisco")) {
            Matcher m = CISCO_MEM.matcher(output);
            if (m.find()) {
                try {
                    long total = Long.parseLong(m.group(1));
                    long used = Long.parseLong(m.group(2));
                    if (total > 0) return String.valueOf(Math.min(100, Math.max(0, (int) (used * 100 / total))));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (vendor.contains("ruijie")) {
            Matcher m = RUIJIE_MEM.matcher(output);
            if (m.find()) return m.group(1).trim();
        }
        Matcher m = HUAWEI_MEM.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("([\\d.]+)\\s*%").matcher(output);
        if (m.find()) return m.group(1).trim();
        return "-";
    }

    /** 华为/H3C {@code display memory-usage} 多版本文案。 */
    private static String parseHuaweiH3cMemory(String output) {
        Matcher m = HUAWEI_MEM.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = HUAWEI_MEM_RATIO.matcher(output);
        if (m.find()) return m.group(1).trim();
        m = HUAWEI_MEM_PCT.matcher(output);
        if (m.find()) return m.group(1).trim();
        // 仅有 Total / Used 字节时推算百分比
        m = Pattern.compile(
                "System Total Memory Is:\\s*(\\d+)\\s*bytes\\s*\\n?\\s*Total Memory Used Is:\\s*(\\d+)\\s*bytes",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(output);
        if (m.find()) {
            try {
                long total = Long.parseLong(m.group(1));
                long used = Long.parseLong(m.group(2));
                if (total > 0) {
                    int pct = (int) Math.round(used * 100.0 / total);
                    return String.valueOf(Math.min(100, Math.max(0, pct)));
                }
            } catch (NumberFormatException ignored) {}
        }
        return "-";
    }
}
