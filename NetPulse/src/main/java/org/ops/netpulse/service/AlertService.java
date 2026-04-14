package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.Device.DeviceStatus;
import org.ops.netpulse.repository.AlertHistoryRepository;
import org.ops.netpulse.repository.AlertRuleRepository;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 告警历史记录：设备上下线触发 + CPU/内存等指标阈值触发。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertService {

    /** 支持 >、=、< 与数字，如 "> 80"、"= 50"、"< 20" */
    private static final Pattern CONDITION_PATTERN = Pattern.compile("\\s*([><]=?|=)\\s*([\\d.]+)\\s*");

    private final AlertRuleRepository alertRuleRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final AuditService auditService;
    @Autowired(required = false)
    private RemediationService remediationService;
    @Autowired(required = false)
    private AlertEmailService alertEmailService;
    @Autowired(required = false)
    private DeviceStatsService deviceStatsService;
    @Autowired(required = false)
    private DeviceRepository deviceRepository;
    @Autowired(required = false)
    private DeviceSshCollectService deviceSshCollectService;
    @Autowired(required = false)
    private SnmpStatsService snmpStatsService;

    /** 指标数据最大允许年龄（毫秒），超过则视为过期、不参与告警评估，只根据「当前当时」的数据判断。默认 2 倍采集间隔。 */
    @Value("${monitor.stats-interval:300}000")
    private long statsIntervalMs;

    /**
     * 设备状态变化时检查 device_status 规则并写入告警历史（三级·一般/提示）。
     */
    @Transactional
    public void onDeviceStatusChange(Device device, DeviceStatus previousStatus, DeviceStatus newStatus) {
        if (device == null || device.getId() == null) return;
        String event = null;
        if (newStatus == DeviceStatus.offline) {
            event = "offline";
        } else if (previousStatus == DeviceStatus.offline && newStatus != DeviceStatus.offline) {
            event = "online";
        }
        if (event == null) return;

        List<AlertRule> rules = alertRuleRepository.findByMetricKeyAndEnabledTrue("device_status");
        String deviceTypeStr = device.getType() != null ? device.getType().toValue() : "other";
        for (AlertRule rule : rules) {
            String ruleCond = rule.getCondition() != null ? rule.getCondition().trim() : "";
            if (!event.equalsIgnoreCase(ruleCond)) continue;
            String types = rule.getDeviceTypes();
            if (types != null && !types.isBlank()) {
                boolean match = false;
                for (String t : types.split(",")) {
                    if (t.trim().equalsIgnoreCase(deviceTypeStr)) { match = true; break; }
                }
                if (!match) continue;
            }
            String msg = "offline".equals(event)
                    ? String.format("设备 %s(%s) 已离线", device.getName(), device.getIp())
                    : String.format("设备 %s(%s) 已上线", device.getName(), device.getIp());
            AlertHistory history = AlertHistory.builder()
                    .ruleId(rule.getId())
                    .deviceId(device.getId())
                    .metricKey("device_status")
                    .triggerValue(event)
                    .startTime(LocalDateTime.now())
                    .status(AlertHistory.AlertStatus.firing)
                    .severity(rule.getSeverity() != null ? rule.getSeverity() : AlertRule.Severity.info)
                    .message(msg)
                    .build();
            alertHistoryRepository.save(history);
            auditService.logAs("SYSTEM_ALERT", "ALERT_HISTORY_CREATED", "alert_history", history.getId(),
                    String.format("规则触发：ruleId=%s, deviceId=%s, metric=device_status, value=%s, severity=%s, msg=%s",
                            rule.getId(), device.getId(), event, history.getSeverity(), msg));
            log.info("Device status alert: ruleId={}, deviceId={}, event={}", rule.getId(), device.getId(), event);
            if (remediationService != null) {
                remediationService.runRemediationAsync(rule, device, history);
            }
            if (alertEmailService != null && Boolean.TRUE.equals(rule.getNotifyEmail())) {
                alertEmailService.sendAlertEmailAsync(history);
            }
        }
    }

    /**
     * 指标类规则（CPU/内存/磁盘）阈值检测：根据当前采集的指标与规则条件写入或恢复告警历史。
     * 使用与前端「实时指标」一致的数据源；仅使用「规则更新时间之后」且未过期的指标，更新规则条件后不会把之前满足旧条件的设备加入告警。
     * 与 DeviceStatsService 采集周期对齐执行。
     */
    @Scheduled(initialDelayString = "${monitor.stats-initial-delay:60}000", fixedDelayString = "${monitor.stats-interval:300}000")
    @Transactional
    public void evaluateMetricRules() {
        if (deviceRepository == null) return;
        List<Device> devices = deviceRepository.findByDeletedFalse();
        // 只根据「当前当时」的指标评估，过期数据不参与，避免把之前满足条件的设备继续算进去
        long maxAgeMs = Math.max(statsIntervalMs * 2, 300_000L);
        Map<Long, DeviceStatsService.DeviceStats> snapshot = buildStatsSnapshotForAlert(devices, maxAgeMs);
        if (snapshot.isEmpty()) {
            int cpuRules = alertRuleRepository.findByMetricKeyAndEnabledTrue("cpu_usage").size();
            int memRules = alertRuleRepository.findByMetricKeyAndEnabledTrue("mem_usage").size();
            int diskRules = alertRuleRepository.findByMetricKeyAndEnabledTrue("disk_usage").size();
            if (cpuRules > 0 || memRules > 0 || diskRules > 0) {
                log.info("告警规则评估跳过：当前无设备指标数据。请配置 Telegraf（InfluxDB）或设备 SSH，使「实时指标」页有 CPU/内存/磁盘数据后，约 5 分钟会触发指标告警。");
            }
            return;
        }
        if (log.isDebugEnabled()) {
            snapshot.forEach((id, s) -> log.debug("告警评估快照 deviceId={} cpu={} mem={} disk={}", id, s.getCpuPercent(), s.getMemoryPercent(), s.getDiskPercent()));
        }

        for (String metricKey : List.of("cpu_usage", "mem_usage", "disk_usage")) {
            List<AlertRule> rules = alertRuleRepository.findByMetricKeyAndEnabledTrue(metricKey);
            if (rules.isEmpty()) continue;
            for (Map.Entry<Long, DeviceStatsService.DeviceStats> e : snapshot.entrySet()) {
                Long deviceId = e.getKey();
                DeviceStatsService.DeviceStats stats = e.getValue();
                if (!isStatsCurrent(stats, maxAgeMs)) continue;
                Double value = "cpu_usage".equals(metricKey) ? stats.getCpuPercent()
                        : "mem_usage".equals(metricKey) ? stats.getMemoryPercent()
                        : stats.getDiskPercent();
                if (value == null) continue;
                Device device = deviceRepository.findById(deviceId).orElse(null);
                if (device == null) continue;
                String deviceTypeStr = device.getType() != null ? device.getType().toValue() : "other";
                for (AlertRule rule : rules) {
                    // 只使用「规则更新时间之后」采集的指标评估，避免更新规则条件后把之前满足旧条件的设备算入告警
                    long ruleUpdateMs = rule.getUpdateTime() != null
                            ? rule.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : 0L;
                    if (ruleUpdateMs > 0 && stats.getUpdatedAt() < ruleUpdateMs) continue;
                    if (rule.getDeviceId() != null && !rule.getDeviceId().equals(deviceId)) {
                        if (log.isTraceEnabled()) log.trace("告警规则 ruleId={} 限定 deviceId={} 与当前设备 {} 不符，跳过", rule.getId(), rule.getDeviceId(), deviceId);
                        continue;
                    }
                    String types = rule.getDeviceTypes();
                    if (types != null && !types.isBlank()) {
                        boolean match = false;
                        for (String t : types.split(",")) {
                            if (t.trim().equalsIgnoreCase(deviceTypeStr)) { match = true; break; }
                        }
                        if (!match) {
                            if (log.isTraceEnabled()) log.trace("告警规则 ruleId={} device_types={} 与设备类型 {} 不匹配，跳过", rule.getId(), types, deviceTypeStr);
                            continue;
                        }
                    }
                    String cond = rule.getCondition() != null ? rule.getCondition().trim() : "";
                    if (cond.isBlank()) continue;
                    boolean firing = matchesThreshold(value, cond);
                    Optional<AlertHistory> existing = alertHistoryRepository
                            .findTopByRuleIdAndDeviceIdAndStatusOrderByStartTimeDesc(rule.getId(), deviceId, AlertHistory.AlertStatus.firing);
                    if (firing) {
                        if (existing.isEmpty()) {
                            String metricName = "cpu_usage".equals(metricKey) ? "CPU 使用率" : "mem_usage".equals(metricKey) ? "内存使用率" : "磁盘使用率";
                            String msg = String.format("设备 %s(%s) %s %.1f%% 超过阈值 %s",
                                    device.getName(), device.getIp(), metricName, value, cond);
                            AlertHistory history = AlertHistory.builder()
                                    .ruleId(rule.getId())
                                    .deviceId(deviceId)
                                    .metricKey(metricKey)
                                    .triggerValue(String.valueOf(value))
                                    .startTime(LocalDateTime.now())
                                    .status(AlertHistory.AlertStatus.firing)
                                    .severity(rule.getSeverity() != null ? rule.getSeverity() : AlertRule.Severity.warning)
                                    .message(msg)
                                    .build();
                            alertHistoryRepository.save(history);
                            auditService.logAs("SYSTEM_ALERT", "ALERT_HISTORY_CREATED", "alert_history", history.getId(),
                                    String.format("规则触发：ruleId=%s, deviceId=%s, metric=%s, value=%.2f, condition=%s, severity=%s, msg=%s",
                                            rule.getId(), deviceId, metricKey, value, cond, history.getSeverity(), msg));
                            log.info("Metric alert: ruleId={}, deviceId={}, {}={}, condition={}", rule.getId(), deviceId, metricKey, value, cond);
                            if (remediationService != null) {
                                remediationService.runRemediationAsync(rule, device, history);
                            }
                            if (alertEmailService != null && Boolean.TRUE.equals(rule.getNotifyEmail())) {
                                alertEmailService.sendAlertEmailAsync(history);
                            }
                        } else if (log.isDebugEnabled()) {
                            log.debug("告警已存在 firing，未重复创建 ruleId={} deviceId={} {}={}", rule.getId(), deviceId, metricKey, value);
                        }
                    } else {
                        existing.ifPresent(h -> {
                            h.setStatus(AlertHistory.AlertStatus.resolved);
                            h.setEndTime(LocalDateTime.now());
                            alertHistoryRepository.save(h);
                            auditService.logAs("SYSTEM_ALERT", "ALERT_HISTORY_RESOLVED", "alert_history", h.getId(),
                                    String.format("告警恢复：ruleId=%s, deviceId=%s, metric=%s, value=%.2f, condition=%s",
                                            rule.getId(), deviceId, metricKey, value, cond));
                        });
                    }
                }
            }
        }
    }

    /** 与前端 /metrics/realtime 一致：Linux 用 DeviceStatsService；网络设备优先 Redis（SNMP），再 SSH；仅保留在 maxAgeMs 内更新的数据。 */
    private Map<Long, DeviceStatsService.DeviceStats> buildStatsSnapshotForAlert(List<Device> devices, long maxAgeMs) {
        Map<Long, DeviceStatsService.DeviceStats> merged = new HashMap<>();
        if (deviceStatsService != null) {
            for (Map.Entry<Long, DeviceStatsService.DeviceStats> entry : deviceStatsService.getStatsSnapshot().entrySet()) {
                if (isStatsCurrent(entry.getValue(), maxAgeMs)) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<Long, DeviceStatsService.DeviceStats> snmpByDeviceId = Map.of();
        if (snmpStatsService != null) {
            try {
                snmpByDeviceId = snmpStatsService.getStatsFromRedisByIp(devices);
            } catch (Exception e) {
                log.debug("告警评估读取 Redis SNMP 失败: {}", e.getMessage());
            }
        }
        long now = System.currentTimeMillis();
        for (Device d : devices) {
            if (d.getId() == null) continue;
            if (d.getType() != null && d.getType() == Device.DeviceType.server) continue;
            if (merged.containsKey(d.getId())) continue;
            String ip = d.getIp() != null ? d.getIp().trim() : "";
            if (ip.isEmpty()) continue;
            DeviceStatsService.DeviceStats snmpRow = snmpByDeviceId.get(d.getId());
            DeviceStatsService.DeviceStats sshRow = deviceSshCollectService != null
                    ? deviceSshCollectService.getStatsByIp(ip) : null;
            NetworkDeviceStatsMerger.Pick pick = NetworkDeviceStatsMerger.pick(now, maxAgeMs, snmpRow, sshRow);
            DeviceStatsService.DeviceStats s = pick.stats();
            if (s != null) {
                merged.put(d.getId(), s);
            }
        }
        return merged;
    }

    /** 是否为当前有效数据：updatedAt 在 maxAgeMs 内有更新，避免用历史数据触发告警 */
    private static boolean isStatsCurrent(DeviceStatsService.DeviceStats stats, long maxAgeMs) {
        if (stats == null || stats.getUpdatedAt() <= 0) return false;
        return System.currentTimeMillis() - stats.getUpdatedAt() <= maxAgeMs;
    }

    /** 解析条件如 "> 20"、">=80"、 "< 10"，判断 value 是否满足 */
    private static boolean matchesThreshold(double value, String condition) {
        if (condition == null || condition.isBlank()) return false;
        java.util.regex.Matcher m = CONDITION_PATTERN.matcher(condition.trim());
        if (!m.matches()) return false;
        String op = m.group(1);
        double threshold;
        try {
            threshold = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (op) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "=" -> Math.abs(value - threshold) < 1e-9;
            default -> false;
        };
    }

    /**
     * 自动修复后立即复检该规则在该设备上的状态：
     * - 若指标已降到阈值内，则把最近一条 firing 告警自动标记为 resolved；
     * - 若仍超阈值，则保持 firing。
     */
    @Transactional
    public void tryAutoResolveAfterRemediation(Long ruleId, Long deviceId) {
        if (ruleId == null || deviceId == null) return;
        AlertRule rule = alertRuleRepository.findById(ruleId).orElse(null);
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) return;
        String metricKey = rule.getMetricKey();
        if (!"cpu_usage".equals(metricKey) && !"mem_usage".equals(metricKey) && !"disk_usage".equals(metricKey)) return;

        Device device = deviceRepository != null ? deviceRepository.findById(deviceId).orElse(null) : null;
        if (device == null) return;

        // 尽量刷新一次当前设备指标，减少自动修复后等待下一轮采集导致的“未及时已处理”。
        tryRefreshStatsNow(device);

        long maxAgeMs = Math.max(statsIntervalMs * 2, 300_000L);
        Map<Long, DeviceStatsService.DeviceStats> snapshot = buildStatsSnapshotForAlert(List.of(device), maxAgeMs);
        DeviceStatsService.DeviceStats stats = snapshot.get(deviceId);
        if (stats == null || !isStatsCurrent(stats, maxAgeMs)) return;

        Double value = "cpu_usage".equals(metricKey) ? stats.getCpuPercent()
                : "mem_usage".equals(metricKey) ? stats.getMemoryPercent()
                : stats.getDiskPercent();
        if (value == null) return;
        boolean stillFiring = matchesThreshold(value, rule.getCondition());
        if (stillFiring) return;

        Optional<AlertHistory> existing = alertHistoryRepository
                .findTopByRuleIdAndDeviceIdAndStatusOrderByStartTimeDesc(ruleId, deviceId, AlertHistory.AlertStatus.firing);
        existing.ifPresent(h -> {
            h.setStatus(AlertHistory.AlertStatus.resolved);
            h.setEndTime(LocalDateTime.now());
            alertHistoryRepository.save(h);
            auditService.logAs("SYSTEM_ALERT", "ALERT_HISTORY_RESOLVED", "alert_history", h.getId(),
                    String.format("自动修复后告警恢复：ruleId=%s, deviceId=%s, metric=%s, value=%.2f, condition=%s",
                            ruleId, deviceId, metricKey, value, rule.getCondition()));
            log.info("自动修复后自动已处理告警：ruleId={} deviceId={} metric={} value={} condition={}",
                    ruleId, deviceId, metricKey, value, rule.getCondition());
        });
    }

    /** 自动修复后立即刷新当前设备指标：Linux 走 SSH，网络设备走 SSH/Telnet 采集缓存。 */
    private void tryRefreshStatsNow(Device device) {
        try {
            if (device.getType() == Device.DeviceType.server) {
                if (deviceStatsService != null && device.getStatus() != Device.DeviceStatus.offline
                        && device.getSshUser() != null && !device.getSshUser().isBlank()
                        && device.getSshPassword() != null && !device.getSshPassword().isBlank()) {
                    deviceStatsService.collectOneSsh(device);
                }
            } else if (deviceSshCollectService != null && device.getId() != null) {
                deviceSshCollectService.collectOne(device.getId());
            }
        } catch (Exception e) {
            log.debug("自动修复后即时采集失败 deviceId={}: {}", device.getId(), e.getMessage());
        }
    }
}
