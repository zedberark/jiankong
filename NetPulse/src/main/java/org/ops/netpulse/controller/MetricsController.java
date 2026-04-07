package org.ops.netpulse.controller;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.service.DeviceService;
import org.ops.netpulse.service.DeviceSshCollectService;
import org.ops.netpulse.service.DeviceStatsService;
import org.ops.netpulse.service.LocalHostStatsService;
import org.ops.netpulse.service.SnmpStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * 指标与监控接口：本机系统监控、设备状态时序、实时指标（含 CPU/内存来自 InfluxDB 与 Redis）、
 * 设备状态时间线、趋势图、一键刷新采集、InfluxDB/Redis 诊断等。InfluxDB/SNMP 服务可选。
 */
@RestController
@RequestMapping("/metrics")
@CrossOrigin
@Slf4j
public class MetricsController {

    /** 趋势查询 InfluxDB 超时秒数 */
    private static final int TREND_QUERY_TIMEOUT_SEC = 10;

    private final DeviceService deviceService;
    private final DeviceStatsService deviceStatsService;
    private final InfluxDBClient influxDBClient;
    private final SnmpStatsService snmpStatsService;
    private final DeviceSshCollectService deviceSshCollectService;
    private final LocalHostStatsService localHostStatsService;

    public MetricsController(DeviceService deviceService,
                             DeviceStatsService deviceStatsService,
                             @Autowired(required = false) InfluxDBClient influxDBClient,
                             @Autowired(required = false) SnmpStatsService snmpStatsService,
                             @Autowired(required = false) DeviceSshCollectService deviceSshCollectService,
                             LocalHostStatsService localHostStatsService) {
        this.deviceService = deviceService;
        this.deviceStatsService = deviceStatsService;
        this.influxDBClient = influxDBClient;
        this.snmpStatsService = snmpStatsService;
        this.deviceSshCollectService = deviceSshCollectService;
        this.localHostStatsService = localHostStatsService;
    }

    /** 本机系统监控（运行监控运维系统的 Windows/Linux 主机）：CPU、内存、磁盘、网络流量 */
    @GetMapping("/local-host")
    public Map<String, Object> getLocalHostStats() {
        return localHostStatsService.getStats();
    }

    @Value("${influxdb.bucket:metrics}")
    private String bucket;

    @Value("${influxdb.org:netpulse}")
    private String influxOrg;

    /** 查询设备最近状态时序，用于仪表盘图表 */
    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<Map<String, Object>>> getDeviceMetrics(
            @PathVariable Long deviceId,
            @RequestParam(defaultValue = "24") int hours
    ) {
        if (influxDBClient == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        String start = Instant.now().minus(hours, ChronoUnit.HOURS).toString();
        String flux = String.format(
                "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"device_status\" and r.device_id == \"%s\") |> filter(fn: (r) => r._field == \"rtt_ms\" or r._field == \"status\")",
                bucket, start, deviceId
        );
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("time", record.getTime() != null ? record.getTime().toString() : null);
                    m.put("field", record.getField());
                    m.put("value", record.getValue());
                    m.put("device_id", record.getValueByKey("device_id"));
                    list.add(m);
                }
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(list);
    }

    /** 最近 24 小时按时间段的在线/离线设备数（每小时一个点，用于首页柱状图）。可选 group：仅统计该分组内设备。 */
    @GetMapping("/device-status-timeline")
    public Map<String, Object> deviceStatusTimeline(@RequestParam(required = false) String group) {
        List<String> labels = new ArrayList<>();
        List<Integer> online = new ArrayList<>();
        List<Integer> offline = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        Set<String> groupDeviceIdSet = null; // 分组下的 device_id 集合（String，与 Influx 一致）
        if (group != null && !group.isBlank() && deviceService != null) {
            List<Device> list = deviceService.findAll(group.trim());
            groupDeviceIdSet = list.stream()
                    .map(Device::getId)
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
        }
        long onlineCurrent = 0, offlineCurrent = 0;
        if (deviceService != null) {
            Map<Device.DeviceStatus, Long> health = deviceService.healthSummary(group != null && !group.isBlank() ? group.trim() : null);
            long on = health.getOrDefault(Device.DeviceStatus.normal, 0L) + health.getOrDefault(Device.DeviceStatus.warning, 0L) + health.getOrDefault(Device.DeviceStatus.critical, 0L);
            long off = health.getOrDefault(Device.DeviceStatus.offline, 0L);
            onlineCurrent = on;
            offlineCurrent = off;
        }
        if (groupDeviceIdSet != null && groupDeviceIdSet.isEmpty()) {
            for (int i = 23; i >= 0; i--) {
                Instant hourStart = now.minus(i, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
                labels.add(hourStart.atZone(zone).getHour() + ":00");
                online.add(0);
                offline.add(0);
            }
            return Map.of("labels", labels, "online", online, "offline", offline);
        }
        if (influxDBClient == null) {
            for (int i = 23; i >= 0; i--) {
                Instant hourStart = now.minus(i, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
                labels.add(hourStart.atZone(zone).getHour() + ":00");
                online.add((int) onlineCurrent);
                offline.add((int) offlineCurrent);
            }
            return Map.of("labels", labels, "online", online, "offline", offline);
        }
        String start = now.minus(24, ChronoUnit.HOURS).toString();
        String flux = String.format(
                "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"device_status\" and r._field == \"status\") |> aggregateWindow(every: 1h, fn: last)",
                bucket, start
        );
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, influxOrg);
            Map<Instant, Map<String, Integer>> hourDeviceStatus = new TreeMap<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant t = record.getTime() != null ? record.getTime().truncatedTo(ChronoUnit.HOURS) : null;
                    if (t == null) continue;
                    String did = String.valueOf(record.getValueByKey("device_id"));
                    if (groupDeviceIdSet != null && !groupDeviceIdSet.contains(did)) continue;
                    Object v = record.getValue();
                    int status = (v instanceof Number && ((Number) v).intValue() == 1) ? 1 : 0;
                    hourDeviceStatus.computeIfAbsent(t, k -> new HashMap<>()).put(did, status);
                }
            }
            for (int i = 23; i >= 0; i--) {
                Instant hourStart = now.minus(i, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
                labels.add(hourStart.atZone(zone).getHour() + ":00");
                Map<String, Integer> devs = hourDeviceStatus.getOrDefault(hourStart, Collections.emptyMap());
                int on = (int) devs.values().stream().filter(x -> x == 1).count();
                int off = (int) devs.values().stream().filter(x -> x == 0).count();
                online.add(on);
                offline.add(off);
            }
        } catch (Exception e) {
            log.warn("device-status-timeline query failed, using current snapshot: {}", e.getMessage());
            for (int i = 23; i >= 0; i--) {
                Instant hourStart = now.minus(i, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
                labels.add(hourStart.atZone(zone).getHour() + ":00");
                online.add((int) onlineCurrent);
                offline.add((int) offlineCurrent);
            }
        }
        return Map.of("labels", labels, "online", online, "offline", offline);
    }

    /** 所有设备最新状态汇总 */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        if (influxDBClient == null) {
            return ResponseEntity.ok(Map.of("up", 0, "down", 0, "devices", List.of()));
        }
        String start = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
        String flux = String.format(
                "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"device_status\" and r._field == \"status\") |> last()",
                bucket, start
        );
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, influxOrg);
            int up = 0, down = 0;
            Set<String> deviceIds = new HashSet<>();
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Object v = record.getValue();
                    String did = String.valueOf(record.getValueByKey("device_id"));
                    deviceIds.add(did);
                    if (v instanceof Number && ((Number) v).intValue() == 1) up++;
                    else down++;
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("up", up);
            result.put("down", down);
            result.put("deviceIds", new ArrayList<>(deviceIds));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("up", 0, "down", 0, "deviceIds", List.of()));
        }
    }

    /** 实时指标仅展示最近 5 分钟内的数据，超过视为过期不返回，避免出现「25 分钟前」等旧数据。 */
    private static final long REALTIME_STATS_MAX_AGE_MS = 5 * 60 * 1000L;

    /** 实时指标：所有设备 + 健康汇总 + CPU/内存；区分数据来源：Linux=InfluxDB/SSH，网络设备=SNMP/Redis；含饼图数据。 */
    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        List<Device> devices = deviceService.findAll();
        Map<String, Long> health = deviceService.healthSummary().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        long now = System.currentTimeMillis();
        Map<Long, DeviceStatsService.DeviceStats> statsMap = deviceStatsService.getStatsSnapshot();
        Map<String, Map<String, Object>> stats = new HashMap<>();
        Map<String, String> statsSource = new HashMap<>();
        for (Map.Entry<Long, DeviceStatsService.DeviceStats> e : statsMap.entrySet()) {
            DeviceStatsService.DeviceStats val = e.getValue();
            if (val.getUpdatedAt() > 0 && (now - val.getUpdatedAt()) > REALTIME_STATS_MAX_AGE_MS) continue;
            Map<String, Object> v = new HashMap<>();
            if (val.getCpuPercent() != null) v.put("cpuPercent", val.getCpuPercent());
            if (val.getMemoryPercent() != null) v.put("memoryPercent", val.getMemoryPercent());
            if (val.getDiskPercent() != null) v.put("diskPercent", val.getDiskPercent());
            if (val.getUpdatedAt() > 0) v.put("updatedAt", val.getUpdatedAt());
            if (!v.isEmpty()) {
                stats.put(String.valueOf(e.getKey()), v);
                statsSource.put(String.valueOf(e.getKey()), "telegraf");
            }
        }
        // 网络设备：优先从 SSH/Telnet 内存缓存按 IP 取（厂商命令采集），无则从 Redis 取（SNMP 等）
        Map<Long, DeviceStatsService.DeviceStats> snmpStats = Map.of();
        if (snmpStatsService != null) {
            try {
                snmpStats = snmpStatsService.getStatsFromRedisByIp(devices);
            } catch (Exception e) {
                // Redis 不可达时降级返回，不影响实时指标主接口可用性
                log.warn("读取 Redis SNMP 指标失败，降级为仅返回本地可用指标: {}", e.getMessage());
            }
        }
        for (Device d : devices) {
            if (d.getType() != null && d.getType() == Device.DeviceType.server) continue;
            if (d.getId() == null || d.getIp() == null || d.getIp().isBlank()) continue;
            String idStr = String.valueOf(d.getId());
            if ("telegraf".equals(statsSource.get(idStr))) continue;
            DeviceStatsService.DeviceStats devStats = null;
            if (deviceSshCollectService != null) {
                devStats = deviceSshCollectService.getStatsByIp(d.getIp().trim());
            }
            if (devStats == null) devStats = snmpStats.get(d.getId());
            if (devStats == null) continue;
            if (devStats.getUpdatedAt() > 0 && (now - devStats.getUpdatedAt()) > REALTIME_STATS_MAX_AGE_MS) continue;
            Map<String, Object> v = new HashMap<>();
            if (devStats.getCpuPercent() != null) v.put("cpuPercent", devStats.getCpuPercent());
            if (devStats.getMemoryPercent() != null) v.put("memoryPercent", devStats.getMemoryPercent());
            if (devStats.getDiskPercent() != null) v.put("diskPercent", devStats.getDiskPercent());
            if (devStats.getUpdatedAt() > 0) v.put("updatedAt", devStats.getUpdatedAt());
            if (!v.isEmpty()) {
                stats.put(idStr, v);
                statsSource.put(idStr, "snmp");
            }
        }
        Map<String, Object> pieData = buildPieData(devices, stats, statsSource);
        Map<String, Object> result = new HashMap<>();
        result.put("devices", devices);
        result.put("summary", health);
        result.put("stats", stats);
        result.put("statsSource", statsSource);
        result.put("pieData", pieData);
        // 有 Redis 即展示网络设备块（从 Redis 按 IP 读，与 snmp.collect.enabled 无关）
        result.put("snmpEnabled", snmpStatsService != null);
        return result;
    }

    /** 饼图数据：数据来源（Linux/网络）、CPU 分布、内存分布 */
    private Map<String, Object> buildPieData(List<Device> devices,
                                             Map<String, Map<String, Object>> stats,
                                             Map<String, String> statsSource) {
        int linuxCount = 0, snmpCount = 0;
        int cpuLow = 0, cpuMid = 0, cpuHigh = 0;
        int memLow = 0, memMid = 0, memHigh = 0;
        for (Device d : devices) {
            String id = String.valueOf(d.getId());
            String source = statsSource.get(id);
            if ("snmp".equals(source)) snmpCount++;
            else if ("telegraf".equals(source)) linuxCount++;
            Map<String, Object> s = stats.get(id);
            if (s != null) {
                Double cpu = s.get("cpuPercent") instanceof Number ? ((Number) s.get("cpuPercent")).doubleValue() : null;
                Double mem = s.get("memoryPercent") instanceof Number ? ((Number) s.get("memoryPercent")).doubleValue() : null;
                if (cpu != null) {
                    if (cpu < 30) cpuLow++; else if (cpu < 70) cpuMid++; else cpuHigh++;
                }
                if (mem != null) {
                    if (mem < 30) memLow++; else if (mem < 70) memMid++; else memHigh++;
                }
            }
        }
        List<Map<String, Object>> sourcePie = List.of(
                Map.<String, Object>of("name", "Linux 设备（InfluxDB/SSH）", "value", linuxCount),
                Map.<String, Object>of("name", "网络设备（Redis）", "value", snmpCount)
        );
        List<Map<String, Object>> cpuPie = List.of(
                Map.<String, Object>of("name", "0-30% 正常", "value", cpuLow),
                Map.<String, Object>of("name", "30-70% 注意", "value", cpuMid),
                Map.<String, Object>of("name", "70-100% 高", "value", cpuHigh)
        );
        List<Map<String, Object>> memPie = List.of(
                Map.<String, Object>of("name", "0-30% 正常", "value", memLow),
                Map.<String, Object>of("name", "30-70% 注意", "value", memMid),
                Map.<String, Object>of("name", "70-100% 高", "value", memHigh)
        );
        return Map.of("source", sourcePie, "cpu", cpuPie, "memory", memPie);
    }

    /** 立即触发一次 CPU/内存采集：Linux 同步；网络设备 SSH/Telnet 异步执行，接口立即返回避免长时间阻塞 */
    @PostMapping("/realtime/refresh-stats")
    public Map<String, Object> refreshStats() {
        deviceStatsService.collectStats();
        if (deviceSshCollectService != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    deviceSshCollectService.collectAllNow();
                } catch (Exception e) {
                    log.warn("异步 SSH/Telnet 采集异常: {}", e.getMessage());
                }
            });
        }
        String msg = deviceSshCollectService != null
                ? "已提交采集任务，请稍后刷新查看"
                : "已触发采集，请刷新页面或等待数秒";
        return Map.of("success", true, "message", msg);
    }

    /** 监控趋势：从 InfluxDB 拉取 Telegraf 的 cpu / mem 时序（按 host 匹配）。host 先按 IP 查，若无数据且传了 hostName 则再按主机名查。 */
    @GetMapping("/trend")
    public Map<String, Object> trend(
            @RequestParam String host,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) Integer minutes,
            @RequestParam(required = false) String hostName) {
        Map<String, Object> empty = new HashMap<>();
        empty.put("cpu", new ArrayList<Map<String, Object>>());
        empty.put("mem", new ArrayList<Map<String, Object>>());
        if (influxDBClient == null) {
            empty.put("hint", "InfluxDB 未配置或不可用，请检查 application-secrets.yml 中的 influxdb.token 与 URL。");
            return empty;
        }
        String rangeStart = (minutes != null && minutes > 0)
                ? "-" + minutes + "m"
                : "-" + hours + "h";
        try {
            Map<String, Object> result = CompletableFuture.supplyAsync(() -> runTrendQuery(host, rangeStart), ForkJoinPool.commonPool())
                    .get(TREND_QUERY_TIMEOUT_SEC, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cpu = (List<Map<String, Object>>) result.get("cpu");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mem = (List<Map<String, Object>>) result.get("mem");
            if ((cpu == null || cpu.isEmpty()) && (mem == null || mem.isEmpty()) && hostName != null && !hostName.isBlank()) {
                result = runTrendQuery(hostName.trim(), rangeStart);
            }
            return result;
        } catch (TimeoutException e) {
            log.warn("InfluxDB trend query timeout ({}s), host={}", TREND_QUERY_TIMEOUT_SEC, host);
            empty.put("hint", "查询超时，请稍后重试或缩短时间范围。");
            return empty;
        } catch (Exception e) {
            log.warn("InfluxDB trend query failed: {}", e.getMessage());
            empty.put("hint", "查询失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            return empty;
        }
    }

    private Map<String, Object> runTrendQuery(String host, String rangeStart) {
        Map<String, Object> result = new HashMap<>();
        result.put("cpu", new ArrayList<Map<String, Object>>());
        result.put("mem", new ArrayList<Map<String, Object>>());
        result.put("hint", null);
        String escapedHost = host.replace("\"", "\\\"");
        try {
            // CPU：优先 usage_active，否则用 100 - usage_idle
            String fluxCpu = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"cpu\" and r.host == \"%s\" and r.cpu == \"cpu-total\" and (r._field == \"usage_active\" or r._field == \"usage_idle\"))",
                    bucket, rangeStart, escapedHost);
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxCpu, influxOrg);
            List<Map<String, Object>> cpuList = new ArrayList<>();
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Object val = r.getValue();
                    if (val instanceof Number) {
                        double v = ((Number) val).doubleValue();
                        if ("usage_idle".equals(r.getField())) v = 100.0 - v;
                        Map<String, Object> m = new HashMap<>();
                        m.put("time", r.getTime() != null ? r.getTime().toString() : null);
                        m.put("value", v);
                        cpuList.add(m);
                    }
                }
            }
            result.put("cpu", cpuList);
            // 内存：used_percent
            String fluxMem = String.format(
                    "from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"mem\" and r.host == \"%s\" and r._field == \"used_percent\")",
                    bucket, rangeStart, escapedHost);
            tables = influxDBClient.getQueryApi().query(fluxMem, influxOrg);
            List<Map<String, Object>> memList = new ArrayList<>();
            for (FluxTable t : tables) {
                for (FluxRecord r : t.getRecords()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("time", r.getTime() != null ? r.getTime().toString() : null);
                    m.put("value", r.getValue());
                    memList.add(m);
                }
            }
            result.put("mem", memList);
            if (cpuList.isEmpty() && memList.isEmpty()) {
                result.put("hint", buildTrendHint(host, rangeStart));
            }
        } catch (Exception e) {
            log.warn("Trend query error: {}", e.getMessage());
            result.put("hint", "查询异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误") + "，请检查 InfluxDB 连接与 bucket=" + bucket + "、org=" + influxOrg);
        }
        return result;
    }

    private String buildTrendHint(String host, String rangeStart) {
        try {
            String fluxAnyCpu = String.format("from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"cpu\") |> limit(n: 1)", bucket, rangeStart);
            List<FluxTable> tCpu = influxDBClient.getQueryApi().query(fluxAnyCpu, influxOrg);
            boolean hasAnyCpu = tCpu != null && tCpu.stream().anyMatch(t -> !t.getRecords().isEmpty());
            String fluxAnyMem = String.format("from(bucket:\"%s\") |> range(start: %s) |> filter(fn: (r) => r._measurement == \"mem\") |> limit(n: 1)", bucket, rangeStart);
            List<FluxTable> tMem = influxDBClient.getQueryApi().query(fluxAnyMem, influxOrg);
            boolean hasAnyMem = tMem != null && tMem.stream().anyMatch(t -> !t.getRecords().isEmpty());
            if (hasAnyCpu || hasAnyMem) {
                return "bucket 内已有 cpu/mem 数据，但 host 与 \"" + host + "\" 不匹配。请确认设备管理里该设备的「名称」与 Telegraf 的 host（或主机名）一致，或 Telegraf [global_tags] host 设为该设备 IP。";
            }
            return "bucket 内暂无 cpu/mem 数据。请确认：1）Telegraf 已配置 [[inputs.cpu]]、[[inputs.mem]]；2）已 systemctl restart telegraf；3）等待 1～2 分钟后再查询。当前 bucket=" + bucket + "，org=" + influxOrg + "。";
        } catch (Exception e) {
            return "无法诊断（" + e.getMessage() + "）。请确认 InfluxDB 可访问、bucket=" + bucket + "、org=" + influxOrg + "，且 Telegraf 已配 cpu/mem 并重启。";
        }
    }
}
