package org.ops.netpulse.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采集并缓存设备的 CPU、内存使用率（主要用于 Linux 服务器）。
 * 优先从 InfluxDB 读取 Telegraf 数据；若该设备无 Telegraf 数据，则通过 SSH 执行 Linux 自带命令采集：
 * 内存使用 <code>free</code>，CPU 使用 <code>/proc/stat</code>（无需安装 Telegraf）。离线设备不参与采集。
 */
@Service
@Slf4j
public class DeviceStatsService {

    private final DeviceRepository deviceRepository;
    private final BatchCommandService batchCommandService;
    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket:metrics}")
    private String bucket;
    @Value("${influxdb.org:netpulse}")
    private String influxOrg;

    private final ConcurrentHashMap<Long, DeviceStats> cache = new ConcurrentHashMap<>();

    public DeviceStatsService(DeviceRepository deviceRepository,
                              BatchCommandService batchCommandService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) InfluxDBClient influxDBClient) {
        this.deviceRepository = deviceRepository;
        this.batchCommandService = batchCommandService;
        this.influxDBClient = influxDBClient;
    }

    @Data
    public static class DeviceStats {
        private Double cpuPercent;
        private Double memoryPercent;
        private Double diskPercent;
        private long updatedAt;
        /** SNMP：ifHCInOctets 各接口 Counter 之和（字符串，避免 Counter64 溢出） */
        private String ifInOctetsTotal;
        /** SNMP：ifHCOutOctets 各接口 Counter 之和 */
        private String ifOutOctetsTotal;
    }

    /** 启动后 1 分钟首次采集，之后每 3 分钟；先读 InfluxDB（Telegraf），再对未命中且在线的设备用 SSH 补采；离线不采集 */
    @Scheduled(initialDelayString = "${monitor.stats-initial-delay:60}000", fixedDelayString = "${monitor.stats-interval:300}000")
    public void collectStats() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        if (influxDBClient != null) {
            collectFromInfluxDB(devices);
        }
        for (Device d : devices) {
            if (d.getStatus() == Device.DeviceStatus.offline) continue; // 离线设备不采集，避免无效请求与写库
            DeviceStats cached = cache.get(d.getId());
            boolean hasCpu = cached != null && cached.getCpuPercent() != null;
            boolean hasMem = cached != null && cached.getMemoryPercent() != null;
            boolean hasDisk = cached != null && cached.getDiskPercent() != null;
            if (hasCpu && hasMem && hasDisk) continue;
            if (d.getSshUser() == null || d.getSshPassword() == null) continue;
            try {
                collectOneSsh(d);
            } catch (Exception e) {
                log.debug("Stats collect failed for device {}: {}", d.getName(), e.getMessage());
            }
        }
    }

    /** 从 InfluxDB 读取 Telegraf 写入的 cpu / mem，按 host 匹配设备。仅查最近 5 分钟并取 last()，超时视为无数据，避免展示过旧指标。 */
    public void collectFromInfluxDB(List<Device> devices) {
        if (influxDBClient == null || devices.isEmpty()) return;
        String start = "-5m";
        Map<String, Double> hostCpu = new HashMap<>();
        Map<String, Long> hostCpuTime = new HashMap<>();
        Map<String, Double> hostMem = new HashMap<>();
        Map<String, Long> hostMemTime = new HashMap<>();
        Map<String, Double> hostDisk = new HashMap<>();
        Map<String, Long> hostDiskTime = new HashMap<>();
        try {
            String fluxCpu = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"cpu\" and r.cpu == \"cpu-total\" and r._field == \"usage_active\") |> last()",
                    bucket, start);
            List<FluxTable> tablesCpu = influxDBClient.getQueryApi().query(fluxCpu, influxOrg);
            for (FluxTable t : tablesCpu) {
                for (FluxRecord r : t.getRecords()) {
                    Object host = r.getValueByKey("host");
                    if (host == null) continue;
                    String h = host.toString().trim();
                    if (r.getValue() instanceof Number) {
                        hostCpu.put(h, ((Number) r.getValue()).doubleValue());
                        if (r.getTime() != null) hostCpuTime.put(h, r.getTime().toEpochMilli());
                    }
                }
            }
            if (hostCpu.isEmpty()) {
                fluxCpu = String.format(
                        "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"cpu\" and r.cpu == \"cpu-total\" and r._field == \"usage_idle\") |> last()",
                        bucket, start);
                tablesCpu = influxDBClient.getQueryApi().query(fluxCpu, influxOrg);
                for (FluxTable t : tablesCpu) {
                    for (FluxRecord r : t.getRecords()) {
                        Object host = r.getValueByKey("host");
                        if (host == null) continue;
                        String h = host.toString().trim();
                        if (r.getValue() instanceof Number) {
                            double idle = ((Number) r.getValue()).doubleValue();
                            hostCpu.put(h, 100.0 - idle);
                            if (r.getTime() != null) hostCpuTime.put(h, r.getTime().toEpochMilli());
                        }
                    }
                }
            }
            String fluxMem = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"mem\" and r._field == \"used_percent\") |> last()",
                    bucket, start);
            List<FluxTable> tablesMem = influxDBClient.getQueryApi().query(fluxMem, influxOrg);
            for (FluxTable t : tablesMem) {
                for (FluxRecord r : t.getRecords()) {
                    Object host = r.getValueByKey("host");
                    if (host == null) continue;
                    String h = host.toString().trim();
                    if (r.getValue() instanceof Number) {
                        hostMem.put(h, ((Number) r.getValue()).doubleValue());
                        if (r.getTime() != null) hostMemTime.put(h, r.getTime().toEpochMilli());
                    }
                }
            }
            // Telegraf disk：根分区使用率（path=="/" 或无 path 时取 last）
            String fluxDisk = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"disk\" and r._field == \"used_percent\") |> last()",
                    bucket, start);
            List<FluxTable> tablesDisk = influxDBClient.getQueryApi().query(fluxDisk, influxOrg);
            for (FluxTable t : tablesDisk) {
                for (FluxRecord r : t.getRecords()) {
                    Object host = r.getValueByKey("host");
                    if (host == null) continue;
                    Object path = r.getValueByKey("path");
                    if (path != null && !"/".equals(path.toString().trim())) continue; // 优先根分区
                    String h = host.toString().trim();
                    if (r.getValue() instanceof Number) {
                        hostDisk.put(h, ((Number) r.getValue()).doubleValue());
                        if (r.getTime() != null) hostDiskTime.put(h, r.getTime().toEpochMilli());
                    }
                }
            }
            if (hostDisk.isEmpty()) {
                tablesDisk = influxDBClient.getQueryApi().query(fluxDisk, influxOrg);
                for (FluxTable t : tablesDisk) {
                    for (FluxRecord r : t.getRecords()) {
                        Object host = r.getValueByKey("host");
                        if (host == null) continue;
                        String h = host.toString().trim();
                        if (r.getValue() instanceof Number && !hostDisk.containsKey(h)) {
                            hostDisk.put(h, ((Number) r.getValue()).doubleValue());
                            if (r.getTime() != null) hostDiskTime.put(h, r.getTime().toEpochMilli());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("InfluxDB stats query failed: {}", e.getMessage());
            return;
        }
        for (Device d : devices) {
            String ip = d.getIp() != null ? d.getIp().trim() : "";
            String name = d.getName() != null ? d.getName().trim() : "";
            Double cpu = hostCpu.get(ip);
            if (cpu == null && !name.isEmpty()) cpu = hostCpu.get(name);
            Double mem = hostMem.get(ip);
            if (mem == null && !name.isEmpty()) mem = hostMem.get(name);
            Double disk = hostDisk.get(ip);
            if (disk == null && !name.isEmpty()) disk = hostDisk.get(name);
            if (cpu != null || mem != null || disk != null) {
                DeviceStats s = cache.getOrDefault(d.getId(), new DeviceStats());
                if (cpu != null) s.setCpuPercent(cpu);
                if (mem != null) s.setMemoryPercent(mem);
                if (disk != null) s.setDiskPercent(disk);
                long cpuTs = hostCpuTime.getOrDefault(ip, 0L);
                if (cpuTs == 0 && name != null) cpuTs = hostCpuTime.getOrDefault(name, 0L);
                long memTs = hostMemTime.getOrDefault(ip, 0L);
                if (memTs == 0 && name != null) memTs = hostMemTime.getOrDefault(name, 0L);
                long diskTs = hostDiskTime.getOrDefault(ip, 0L);
                if (diskTs == 0 && name != null) diskTs = hostDiskTime.getOrDefault(name, 0L);
                long dataTime = Math.max(Math.max(cpuTs, memTs), diskTs);
                s.setUpdatedAt(dataTime > 0 ? dataTime : System.currentTimeMillis());
                cache.put(d.getId(), s);
            }
        }
        // 若按 IP/名称未匹配到：仅当「恰好 1 个 server 设备」且「恰好 1 个 InfluxDB host 有数据」时，将该 host 数据赋给该设备（与趋势能查到数据时一致）
        Set<String> matchedHosts = new HashSet<>();
        for (Device d : devices) {
            String ip = d.getIp() != null ? d.getIp().trim() : "";
            String name = d.getName() != null ? d.getName().trim() : "";
            if (hostCpu.containsKey(ip)) matchedHosts.add(ip);
            if (!name.isEmpty() && hostCpu.containsKey(name)) matchedHosts.add(name);
        }
        Set<String> allHostsWithData = new HashSet<>(hostCpu.keySet());
        allHostsWithData.removeAll(matchedHosts);
        List<Device> serverDevices = devices.stream()
                .filter(d -> d.getType() != null && "server".equalsIgnoreCase(d.getType().toValue()))
                .toList();
        java.util.List<Device> serversWithoutStats = serverDevices.stream()
                .filter(d -> {
                    DeviceStats cs = cache.get(d.getId());
                    return cs == null || (cs.getCpuPercent() == null && cs.getMemoryPercent() == null && cs.getDiskPercent() == null);
                })
                .toList();
        if (allHostsWithData.size() == 1 && serversWithoutStats.size() == 1) {
            String fallbackHost = allHostsWithData.iterator().next();
            Device d = serversWithoutStats.get(0);
            Double cpu = hostCpu.get(fallbackHost);
            Double mem = hostMem.get(fallbackHost);
            Double disk = hostDisk.get(fallbackHost);
            if (cpu != null || mem != null || disk != null) {
                DeviceStats s = cache.getOrDefault(d.getId(), new DeviceStats());
                if (cpu != null) s.setCpuPercent(cpu);
                if (mem != null) s.setMemoryPercent(mem);
                if (disk != null) s.setDiskPercent(disk);
                long dataTime = Math.max(Math.max(hostCpuTime.getOrDefault(fallbackHost, 0L), hostMemTime.getOrDefault(fallbackHost, 0L)), hostDiskTime.getOrDefault(fallbackHost, 0L));
                s.setUpdatedAt(dataTime > 0 ? dataTime : System.currentTimeMillis());
                cache.put(d.getId(), s);
                log.debug("InfluxDB host \"{}\" 未与设备名/IP 匹配，已按「单 server 单 host」赋给设备 id={} name={}", fallbackHost, d.getId(), d.getName());
            }
        }
    }

    /** 返回 InfluxDB 最近 5 分钟内 cpu/mem 数据里出现过的 host 列表，用于排查「实时指标无数据」：设备名称或 IP 需与其中某一项完全一致。 */
    public java.util.List<String> listInfluxDbHostsForStats() {
        if (influxDBClient == null) return java.util.Collections.emptyList();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        String start = "-5m";
        try {
            String fluxCpu = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"cpu\" and r.cpu == \"cpu-total\") |> last()",
                    bucket, start);
            for (FluxTable t : influxDBClient.getQueryApi().query(fluxCpu, influxOrg)) {
                for (FluxRecord r : t.getRecords()) {
                    Object host = r.getValueByKey("host");
                    if (host != null && !host.toString().isBlank()) hosts.add(host.toString().trim());
                }
            }
            String fluxMem = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"mem\") |> last()",
                    bucket, start);
            for (FluxTable t : influxDBClient.getQueryApi().query(fluxMem, influxOrg)) {
                for (FluxRecord r : t.getRecords()) {
                    Object host = r.getValueByKey("host");
                    if (host != null && !host.toString().isBlank()) hosts.add(host.toString().trim());
                }
            }
        } catch (Exception e) {
            log.debug("List InfluxDB hosts failed: {}", e.getMessage());
        }
        java.util.List<String> list = new java.util.ArrayList<>(hosts);
        java.util.Collections.sort(list);
        return list;
    }

    @Async
    public void collectOneAsync(Device d) {
        try { collectOneSsh(d); } catch (Exception e) { log.trace("Stats collect failed: {}", e.getMessage()); }
    }

    /** SSH 方式采集单设备 CPU/内存/磁盘（InfluxDB/Telegraf 无数据时的回退），使用 Linux 自带命令：free、/proc/stat、df */
    public void collectOneSsh(Device d) {
        Double memory = parsePercent(batchCommandService.runCommand(d, memoryCmd()));
        Double cpu = parsePercent(batchCommandService.runCommand(d, cpuCmd()));
        Double disk = parsePercent(batchCommandService.runCommand(d, diskCmd()));
        if (memory != null || cpu != null || disk != null) {
            DeviceStats s = cache.getOrDefault(d.getId(), new DeviceStats());
            if (cpu != null) s.setCpuPercent(cpu);
            if (memory != null) s.setMemoryPercent(memory);
            if (disk != null) s.setDiskPercent(disk);
            s.setUpdatedAt(System.currentTimeMillis());
            cache.put(d.getId(), s);
        }
    }

    /** Linux 自带命令：内存使用率（free 解析 Mem 行） */
    private static String memoryCmd() {
        return "free 2>/dev/null | awk '/^Mem:/{if($2>0) printf \"%.1f\", $3*100/$2}'";
    }

    /** Linux 自带命令：CPU 使用率（/proc/stat 解析 cpu 行，user+nice+system / total） */
    private static String cpuCmd() {
        return "awk '/^cpu /{u=$2+$3+$4; t=u+$5+$6+$7+$8; if(t>0) printf \"%.1f\", u*100/t}' /proc/stat 2>/dev/null";
    }

    /** Linux 自带命令：磁盘使用率（根分区 used*100/total）。df -P 为 POSIX，兼容 Alpine/BusyBox；无 / 时用 df -P 匹配挂载点 / */
    private static String diskCmd() {
        return "a=$(df -P / 2>/dev/null | awk 'NR==2 && $2+0>0 {printf \"%.1f\", ($3+0)*100/($2+0)}'); "
            + "if [ -n \"$a\" ]; then echo \"$a\"; else df -P 2>/dev/null | awk '$NF==\"/\" && $2+0>0 {printf \"%.1f\", ($3+0)*100/($2+0); exit}'; fi";
    }

    private static Double parsePercent(String out) {
        if (out == null || out.isBlank()) return null;
        try {
            return Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 返回 deviceId -> { cpuPercent, memoryPercent, diskPercent }，供实时指标页使用 */
    public Map<Long, DeviceStats> getStatsSnapshot() {
        return new HashMap<>(cache);
    }
}
