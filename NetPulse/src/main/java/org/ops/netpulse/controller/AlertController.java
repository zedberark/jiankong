package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.AlertTemplate;
import org.ops.netpulse.repository.AlertHistoryRepository;
import org.ops.netpulse.repository.AlertRuleRepository;
import org.ops.netpulse.repository.AlertTemplateRepository;
import org.ops.netpulse.service.AlertEmailService;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.DeviceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 告警通知：规则增删改、告警历史 */
@RestController
@RequestMapping("/alerts")
@RequiredArgsConstructor
@CrossOrigin
public class AlertController {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertTemplateRepository alertTemplateRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final AuditService auditService;
    private final DeviceService deviceService;
    private final AlertEmailService alertEmailService;

    /** 告警通知渠道配置：邮箱开关、收件地址、SMTP */
    @GetMapping("/notify-settings")
    public Map<String, String> getNotifySettings() {
        return alertEmailService.getNotifySettings();
    }

    /** 保存告警通知渠道配置（邮箱开关、SMTP、收件人等） */
    @PutMapping("/notify-settings")
    public ResponseEntity<Void> saveNotifySettings(@RequestBody Map<String, Object> body) {
        alertEmailService.saveNotifySettings(body != null ? body : new HashMap<>());
        auditService.log("SAVE_ALERT_NOTIFY_SETTINGS", "system_config", null, "email");
        return ResponseEntity.ok().build();
    }

    /** 告警规则列表（全部规则，供前端规则管理页展示） */
    @GetMapping("/rules")
    public List<AlertRule> listRules() {
        return alertRuleRepository.findAll();
    }

    /** 新增告警规则（指标条件、设备上下线、严重程度、邮件通知、自动修复等） */
    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody Map<String, Object> body) {
        String name = body != null && body.get("name") != null ? Objects.toString(body.get("name")).trim() : "";
        if (name.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "请填写规则名称"));
        AlertRule rule = new AlertRule();
        rule.setId(null);
        rule.setName(name);
        rule.setDeviceId(body.get("deviceId") != null ? numberOrNull(body.get("deviceId"), Long.class) : null);
        String metricKey = trimStr(body.get("metricKey"), 50, "cpu_usage");
        rule.setMetricKey(metricKey.isEmpty() ? "cpu_usage" : metricKey);
        rule.setCondition(trimStr(body.get("condition"), 255, "> 80"));
        if (rule.getCondition().isEmpty()) rule.setCondition("> 80");
        rule.setSeverity(severityFrom(body.get("severity")));
        rule.setDeviceTypes(trimStr(body.get("deviceTypes"), 64, null));
        rule.setEnabled(body.get("enabled") == null || Boolean.TRUE.equals(body.get("enabled")));
        rule.setNotifyEmail(Boolean.TRUE.equals(body.get("notifyEmail")));
        rule.setAutoFixEnabled(Boolean.TRUE.equals(body.get("autoFixEnabled")));
        rule.setAutoFixType(trimStr(body.get("autoFixType"), 32, "ssh_command"));
        rule.setAutoFixCommand(body.get("autoFixCommand") != null ? Objects.toString(body.get("autoFixCommand")).trim() : null);
        if (rule.getAutoFixCommand() != null && rule.getAutoFixCommand().isEmpty()) rule.setAutoFixCommand(null);
        try {
            AlertRule saved = alertRuleRepository.save(rule);
            auditService.log("CREATE_ALERT_RULE", "alert_rule", saved.getId(), "name=" + saved.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "保存失败：" + (e.getMessage() != null ? e.getMessage() : "请检查必填项与格式")));
        }
    }

    private static String trimStr(Object o, int maxLen, String def) {
        if (o == null) return def;
        String s = Objects.toString(o).trim();
        return s.isEmpty() ? def : (s.length() > maxLen ? s.substring(0, maxLen) : s);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Number> T numberOrNull(Object o, Class<T> type) {
        if (o == null) return null;
        if (o instanceof Number n) {
            if (type == Long.class) return (T) Long.valueOf(n.longValue());
            if (type == Integer.class) return (T) Integer.valueOf(n.intValue());
        }
        try {
            if (type == Long.class) return (T) Long.valueOf(Long.parseLong(Objects.toString(o).trim()));
            if (type == Integer.class) return (T) Integer.valueOf(Integer.parseInt(Objects.toString(o).trim()));
        } catch (NumberFormatException e) { return null; }
        return null;
    }

    private static AlertRule.Severity severityFrom(Object o) {
        if (o == null) return AlertRule.Severity.warning;
        String s = Objects.toString(o).trim().toLowerCase();
        if ("info".equals(s)) return AlertRule.Severity.info;
        if ("critical".equals(s)) return AlertRule.Severity.critical;
        return AlertRule.Severity.warning;
    }

    // ===== 告警模板（AlertTemplate） =====

    @GetMapping("/templates")
    public List<AlertTemplate> listTemplates() {
        return alertTemplateRepository.findAll();
    }

    @PostMapping("/templates")
    public ResponseEntity<?> createTemplate(@RequestBody Map<String, Object> body) {
        String name = body != null && body.get("name") != null ? Objects.toString(body.get("name")).trim() : "";
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请填写模板名称"));
        }
        String metricKey = trimStr(body.get("metricKey"), 50, "cpu_usage");
        if (metricKey.isEmpty()) metricKey = "cpu_usage";
        String condition = trimStr(body.get("condition"), 255, "> 80");
        if (condition.isEmpty()) condition = "> 80";
        AlertRule.Severity severity = severityFrom(body.get("severity"));
        String deviceTypes = trimStr(body.get("deviceTypes"), 64, null);
        AlertTemplate tpl = AlertTemplate.builder()
                .name(name)
                .metricKey(metricKey)
                .condition(condition)
                .severity(severity)
                .deviceTypes(deviceTypes)
                .build();
        AlertTemplate saved = alertTemplateRepository.save(tpl);
        auditService.log("CREATE_ALERT_TEMPLATE", "alert_template", saved.getId(), "name=" + saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule rule) {
        AlertRule existing = alertRuleRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        existing.setName(rule.getName() != null ? rule.getName() : existing.getName());
        existing.setDeviceId(rule.getDeviceId());
        existing.setMetricKey(rule.getMetricKey() != null ? rule.getMetricKey() : existing.getMetricKey());
        existing.setCondition(rule.getCondition() != null ? rule.getCondition() : existing.getCondition());
        existing.setSeverity(rule.getSeverity() != null ? rule.getSeverity() : existing.getSeverity());
        existing.setDeviceTypes(rule.getDeviceTypes());
        existing.setEnabled(rule.getEnabled() != null ? rule.getEnabled() : existing.getEnabled());
        if (rule.getNotifyEmail() != null) existing.setNotifyEmail(rule.getNotifyEmail());
        if (rule.getAutoFixEnabled() != null) existing.setAutoFixEnabled(rule.getAutoFixEnabled());
        if (rule.getAutoFixType() != null) existing.setAutoFixType(rule.getAutoFixType().length() > 32 ? rule.getAutoFixType().substring(0, 32) : rule.getAutoFixType());
        if (rule.getAutoFixCommand() != null) existing.setAutoFixCommand(rule.getAutoFixCommand().trim().isEmpty() ? null : rule.getAutoFixCommand().trim());
        AlertRule saved = alertRuleRepository.save(existing);
        auditService.log("UPDATE_ALERT_RULE", "alert_rule", id, "name=" + (saved.getName() != null ? saved.getName() : ""));
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        if (!alertRuleRepository.existsById(id)) return ResponseEntity.notFound().build();
        alertRuleRepository.deleteById(id);
        auditService.log("DELETE_ALERT_RULE", "alert_rule", id, "id=" + id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history")
    public Page<AlertHistory> listHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        var pageable = PageRequest.of(page, Math.min(Math.max(1, size), 100));
        AlertHistory.AlertStatus statusEnum = parseStatus(status);
        AlertRule.Severity severityEnum = parseSeverity(severity);
        if (statusEnum != null && severityEnum != null) {
            return alertHistoryRepository.findByStatusAndSeverityOrderByStartTimeDesc(statusEnum, severityEnum, pageable);
        }
        if (statusEnum != null) {
            return alertHistoryRepository.findByStatusOrderByStartTimeDesc(statusEnum, pageable);
        }
        if (severityEnum != null) {
            return alertHistoryRepository.findBySeverityOrderByStatusAscStartTimeDesc(severityEnum, pageable);
        }
        return alertHistoryRepository.findByOrderByStatusAscStartTimeDesc(pageable);
    }

    private static AlertHistory.AlertStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return AlertHistory.AlertStatus.valueOf(status.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AlertRule.Severity parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return null;
        try {
            return AlertRule.Severity.valueOf(severity.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 手工处理告警：将 firing 标记为 resolved（一二三级均可）。保留两路径兼容旧前端。 */
    @PostMapping({ "/resolve/{id}", "/history/{id}/resolve" })
    public ResponseEntity<AlertHistory> resolve(@PathVariable Long id) {
        AlertHistory history = alertHistoryRepository.findById(id).orElse(null);
        if (history == null) return ResponseEntity.notFound().build();
        if (history.getStatus() == AlertHistory.AlertStatus.resolved) {
            return ResponseEntity.ok(history);
        }
        history.setStatus(AlertHistory.AlertStatus.resolved);
        history.setEndTime(java.time.LocalDateTime.now());
        AlertHistory saved = alertHistoryRepository.save(history);
        auditService.log("RESOLVE_ALERT", "alert_history", id, "severity=" + saved.getSeverity());
        return ResponseEntity.ok(saved);
    }

    /** 批量标记已处理：将多条告警历史（仅 firing）标记为 resolved */
    @PostMapping("/history/batch-resolve")
    public Map<String, Object> batchResolve(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> raw = body != null ? (List<Number>) body.get("ids") : null;
        if (raw == null || raw.isEmpty()) {
            return Map.of("resolved", 0, "message", "请选择要处理的记录");
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int count = 0;
        for (Number n : raw) {
            if (n == null) continue;
            Long id = n.longValue();
            AlertHistory h = alertHistoryRepository.findById(id).orElse(null);
            if (h != null && h.getStatus() == AlertHistory.AlertStatus.firing) {
                h.setStatus(AlertHistory.AlertStatus.resolved);
                h.setEndTime(now);
                alertHistoryRepository.save(h);
                count++;
                auditService.log("RESOLVE_ALERT", "alert_history", id, "batch");
            }
        }
        return Map.of("resolved", count, "message", "已批量处理 " + count + " 条");
    }

    /** 告警通知汇总：规则数、当前一二三级告警数量、最近告警列表。可选 group：仅统计该分组内设备的告警。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String group) {
        Map<String, Object> m = new HashMap<>();
        m.put("ruleCount", alertRuleRepository.count());
        AlertHistory.AlertStatus firing = AlertHistory.AlertStatus.firing;
        List<Long> deviceIds = null;
        if (group != null && !group.isBlank() && deviceService != null) {
            deviceIds = deviceService.findAll(group.trim()).stream()
                    .map(org.ops.netpulse.entity.Device::getId)
                    .filter(Objects::nonNull)
                    .toList();
        }
        long criticalCount;
        long warningCount;
        long infoCount;
        List<AlertHistory> recent;
        if (deviceIds != null && deviceIds.isEmpty()) {
            criticalCount = 0;
            warningCount = 0;
            infoCount = 0;
            recent = List.of();
        } else if (deviceIds != null) {
            criticalCount = alertHistoryRepository.countByStatusAndSeverityAndDeviceIdIn(firing, AlertRule.Severity.critical, deviceIds);
            warningCount = alertHistoryRepository.countByStatusAndSeverityAndDeviceIdIn(firing, AlertRule.Severity.warning, deviceIds);
            infoCount = alertHistoryRepository.countByStatusAndSeverityAndDeviceIdIn(firing, AlertRule.Severity.info, deviceIds);
            recent = alertHistoryRepository.findBySeverityInAndStatusAndDeviceIdInOrderByStartTimeDesc(
                    List.of(AlertRule.Severity.critical, AlertRule.Severity.warning, AlertRule.Severity.info),
                    firing, deviceIds, PageRequest.of(0, 10));
        } else {
            criticalCount = alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.critical);
            warningCount = alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.warning);
            infoCount = alertHistoryRepository.countByStatusAndSeverity(firing, AlertRule.Severity.info);
            recent = alertHistoryRepository.findBySeverityInAndStatusOrderByStartTimeDesc(
                    List.of(AlertRule.Severity.critical, AlertRule.Severity.warning, AlertRule.Severity.info),
                    firing, PageRequest.of(0, 10));
        }
        m.put("criticalCount", criticalCount);
        m.put("warningCount", warningCount);
        m.put("infoCount", infoCount);
        m.put("recentHistory", recent);
        return m;
    }
}
