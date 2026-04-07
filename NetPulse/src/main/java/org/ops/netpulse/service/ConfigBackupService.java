package org.ops.netpulse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.ConfigBackup;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.SystemConfig;
import org.ops.netpulse.repository.AlertRuleRepository;
import org.ops.netpulse.repository.ConfigBackupRepository;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 配置备份：支持全量、仅设备、仅告警规则、仅系统配置。
 * 备份内容为 JSON，敏感字段（如 SSH 密码、Token）以 *** 脱敏。
 */
@Service
@RequiredArgsConstructor
public class ConfigBackupService {

    private static final String TYPE_FULL = "full";
    private static final String TYPE_DEVICES = "devices";
    private static final String TYPE_ALERTS = "alerts";
    private static final String TYPE_SYSTEM = "system";
    private static final Set<String> SENSITIVE_KEYS = Set.of("token", "password", "secret", "key");

    private final ConfigBackupRepository backupRepository;
    private final DeviceRepository deviceRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    /** 备份列表，按创建时间倒序 */
    public List<ConfigBackup> list() {
        return backupRepository.findAllByOrderByCreateTimeDesc();
    }

    /** 按 ID 查询单条备份 */
    public Optional<ConfigBackup> findById(Long id) {
        return backupRepository.findById(id);
    }

    /** 创建备份：类型为 full/devices/alerts/system，敏感字段脱敏后序列化为 JSON 存储 */
    @Transactional
    public ConfigBackup createBackup(String name, String backupType, Long userId) {
        String type = backupType != null && !backupType.isBlank() ? backupType.trim().toLowerCase() : TYPE_FULL;
        if (!Set.of(TYPE_FULL, TYPE_DEVICES, TYPE_ALERTS, TYPE_SYSTEM).contains(type)) {
            type = TYPE_FULL;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "1.0");
        root.put("exportTime", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("backupType", type);

        int deviceCount = 0, alertCount = 0, configCount = 0;

        if (TYPE_FULL.equals(type) || TYPE_DEVICES.equals(type)) {
            List<Device> devices = deviceRepository.findByDeletedFalse();
            List<Map<String, Object>> deviceList = devices.stream().map(this::deviceToMap).collect(Collectors.toList());
            root.put("devices", deviceList);
            deviceCount = deviceList.size();
        }
        if (TYPE_FULL.equals(type) || TYPE_ALERTS.equals(type)) {
            List<AlertRule> rules = alertRuleRepository.findAll();
            List<Map<String, Object>> ruleList = rules.stream().map(this::alertRuleToMap).collect(Collectors.toList());
            root.put("alertRules", ruleList);
            alertCount = ruleList.size();
        }
        if (TYPE_FULL.equals(type) || TYPE_SYSTEM.equals(type)) {
            List<SystemConfig> configs = systemConfigRepository.findAll();
            Map<String, String> configMap = new LinkedHashMap<>();
            for (SystemConfig c : configs) {
                String key = c.getConfigKey();
                String value = c.getConfigValue();
                if (key != null && isSensitiveKey(key)) value = "***";
                configMap.put(key, value);
            }
            root.put("systemConfig", configMap);
            configCount = configMap.size();
        }

        String content;
        try {
            content = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("序列化备份内容失败", e);
        }

        String summary = buildSummary(type, deviceCount, alertCount, configCount);
        ConfigBackup backup = ConfigBackup.builder()
                .name(name != null && !name.isBlank() ? name : "backup-" + System.currentTimeMillis())
                .backupType(type)
                .summary(summary)
                .content(content)
                .userId(userId)
                .build();
        return backupRepository.save(backup);
    }

    private Map<String, Object> deviceToMap(Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("ip", d.getIp());
        m.put("type", d.getType() != null ? d.getType().toValue() : "other");
        m.put("vendor", d.getVendor());
        m.put("model", d.getModel());
        m.put("groupName", d.getGroupName());
        m.put("remark", d.getRemark());
        m.put("sshPort", d.getSshPort());
        m.put("snmpPort", d.getSnmpPort());
        m.put("sshUser", d.getSshUser());
        m.put("sshPassword", d.getSshPassword() != null && !d.getSshPassword().isEmpty() ? "***" : "");
        m.put("snmpCommunity", d.getSnmpCommunity());
        return m;
    }

    private Map<String, Object> alertRuleToMap(AlertRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("metricKey", r.getMetricKey());
        m.put("condition", r.getCondition());
        m.put("severity", r.getSeverity() != null ? r.getSeverity().name() : "warning");
        m.put("deviceTypes", r.getDeviceTypes());
        m.put("enabled", r.getEnabled());
        m.put("notifyEmail", Boolean.TRUE.equals(r.getNotifyEmail()));
        return m;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }

    private String buildSummary(String type, int devices, int alerts, int configs) {
        List<String> parts = new ArrayList<>();
        if (TYPE_FULL.equals(type) || TYPE_DEVICES.equals(type)) parts.add("设备 " + devices + " 条");
        if (TYPE_FULL.equals(type) || TYPE_ALERTS.equals(type)) parts.add("告警规则 " + alerts + " 条");
        if (TYPE_FULL.equals(type) || TYPE_SYSTEM.equals(type)) parts.add("系统配置 " + configs + " 项");
        return String.join(", ", parts);
    }

    @Transactional
    public void deleteById(Long id) {
        backupRepository.deleteById(id);
    }

    /**
     * 导入外部备份 JSON：保存为一条新的 ConfigBackup 记录，不立即还原。
     */
    @Transactional
    public ConfigBackup importBackup(String name, String content, Long userId) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("备份内容不能为空");
        }
        Map<String, Object> root = parseBackupContent(content);
        String type = normalizeType(asString(root.get("backupType"), TYPE_FULL));
        String importName = (name != null && !name.isBlank()) ? name.trim() : "import-" + System.currentTimeMillis();
        int deviceCount = asList(root.get("devices")).size();
        int alertCount = asList(root.get("alertRules")).size();
        int configCount = asMap(root.get("systemConfig")).size();

        ConfigBackup backup = ConfigBackup.builder()
                .name(importName)
                .backupType(type)
                .summary(buildSummary(type, deviceCount, alertCount, configCount))
                .content(content)
                .userId(userId)
                .build();
        return backupRepository.save(backup);
    }

    /**
     * 按备份 ID 执行还原（按 backupType 局部或全量还原）。
     * 还原策略：
     * - 设备：按 IP 合并（存在则更新，不存在则新增）；
     * - 告警规则：按 名称+指标+条件 合并；
     * - 系统配置：按 config_key 合并；
     * - 脱敏字段（***）不会覆盖真实密码/密钥。
     */
    @Transactional
    public Map<String, Object> restoreBackup(Long backupId) {
        ConfigBackup backup = backupRepository.findById(backupId)
                .orElseThrow(() -> new IllegalArgumentException("备份不存在"));
        Map<String, Object> root = parseBackupContent(backup.getContent());
        String type = normalizeType(asString(root.get("backupType"), backup.getBackupType()));

        int restoredDevices = 0;
        int restoredAlerts = 0;
        int restoredConfigs = 0;

        if (TYPE_FULL.equals(type) || TYPE_DEVICES.equals(type)) {
            restoredDevices = restoreDevices(asList(root.get("devices")));
        }
        if (TYPE_FULL.equals(type) || TYPE_ALERTS.equals(type)) {
            restoredAlerts = restoreAlertRules(asList(root.get("alertRules")));
        }
        if (TYPE_FULL.equals(type) || TYPE_SYSTEM.equals(type)) {
            restoredConfigs = restoreSystemConfigs(asMap(root.get("systemConfig")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backupId", backupId);
        result.put("backupType", type);
        result.put("restoredDevices", restoredDevices);
        result.put("restoredAlertRules", restoredAlerts);
        result.put("restoredSystemConfigs", restoredConfigs);
        result.put("message", "还原完成");
        return result;
    }

    private Map<String, Object> parseBackupContent(String content) {
        try {
            return objectMapper.readValue(content, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("备份内容格式无效，无法解析 JSON");
        }
    }

    private String normalizeType(String type) {
        String t = type != null ? type.trim().toLowerCase() : TYPE_FULL;
        if (!Set.of(TYPE_FULL, TYPE_DEVICES, TYPE_ALERTS, TYPE_SYSTEM).contains(t)) {
            return TYPE_FULL;
        }
        return t;
    }

    private int restoreDevices(List<Object> rawDevices) {
        if (rawDevices.isEmpty()) return 0;
        List<Device> existing = deviceRepository.findByDeletedFalse();
        Map<String, Device> byIp = new HashMap<>();
        for (Device d : existing) {
            if (d.getIp() != null && !d.getIp().isBlank()) byIp.put(d.getIp().trim(), d);
        }

        int count = 0;
        for (Object obj : rawDevices) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String ip = asString(map.get("ip"), "");
            if (ip.isBlank()) continue;
            Device d = byIp.get(ip);
            if (d == null) {
                d = new Device();
                d.setIp(ip.trim());
            }
            d.setName(asString(map.get("name"), d.getName() != null ? d.getName() : "未命名设备"));
            d.setType(parseDeviceType(asString(map.get("type"), d.getType() != null ? d.getType().toValue() : "other")));
            d.setVendor(asString(map.get("vendor"), d.getVendor()));
            d.setModel(asString(map.get("model"), d.getModel()));
            d.setGroupName(asString(map.get("groupName"), d.getGroupName()));
            d.setRemark(asString(map.get("remark"), d.getRemark()));
            d.setSshPort(asInteger(map.get("sshPort"), d.getSshPort() != null ? d.getSshPort() : 22));
            d.setSnmpPort(asInteger(map.get("snmpPort"), d.getSnmpPort() != null ? d.getSnmpPort() : 161));
            d.setSshUser(asString(map.get("sshUser"), d.getSshUser()));
            String sshPassword = asString(map.get("sshPassword"), null);
            if (sshPassword != null && !sshPassword.isBlank() && !"***".equals(sshPassword)) {
                d.setSshPassword(sshPassword);
            }
            String snmpCommunity = asString(map.get("snmpCommunity"), null);
            if (snmpCommunity != null && !snmpCommunity.isBlank() && !"***".equals(snmpCommunity)) {
                d.setSnmpCommunity(snmpCommunity);
            }
            if (d.getDeleted() == null || d.getDeleted()) d.setDeleted(false);
            if (d.getStatus() == null) d.setStatus(Device.DeviceStatus.offline);
            if (d.getSnmpVersion() == null) d.setSnmpVersion(Device.SnmpVersion.v2c);
            if (d.getSshPort() == null) d.setSshPort(22);
            if (d.getSnmpPort() == null) d.setSnmpPort(161);
            Device saved = deviceRepository.save(d);
            byIp.put(saved.getIp().trim(), saved);
            count++;
        }
        return count;
    }

    private int restoreAlertRules(List<Object> rawRules) {
        if (rawRules.isEmpty()) return 0;
        List<AlertRule> existing = alertRuleRepository.findAll();
        int count = 0;
        for (Object obj : rawRules) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            String name = asString(map.get("name"), "").trim();
            String metricKey = asString(map.get("metricKey"), "").trim();
            String condition = asString(map.get("condition"), "").trim();
            if (name.isEmpty() || metricKey.isEmpty() || condition.isEmpty()) continue;

            AlertRule rule = existing.stream()
                    .filter(r -> name.equals(r.getName()) && metricKey.equals(r.getMetricKey()) && condition.equals(r.getCondition()))
                    .findFirst()
                    .orElseGet(AlertRule::new);

            rule.setName(name);
            rule.setMetricKey(metricKey);
            rule.setCondition(condition);
            rule.setDeviceTypes(asString(map.get("deviceTypes"), rule.getDeviceTypes()));
            rule.setEnabled(asBoolean(map.get("enabled"), rule.getEnabled() != null ? rule.getEnabled() : true));
            rule.setNotifyEmail(asBoolean(map.get("notifyEmail"), rule.getNotifyEmail() != null ? rule.getNotifyEmail() : false));
            String severityStr = asString(map.get("severity"), "warning");
            try {
                rule.setSeverity(AlertRule.Severity.valueOf(severityStr.toLowerCase()));
            } catch (Exception e) {
                rule.setSeverity(AlertRule.Severity.warning);
            }
            alertRuleRepository.save(rule);
            count++;
        }
        return count;
    }

    private int restoreSystemConfigs(Map<String, Object> systemConfig) {
        if (systemConfig.isEmpty()) return 0;
        int count = 0;
        for (Map.Entry<String, Object> e : systemConfig.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if ("***".equals(value) && isSensitiveKey(key)) {
                // 脱敏值不覆盖现有真实密钥
                continue;
            }
            SystemConfig config = systemConfigRepository.findByConfigKey(key).orElseGet(SystemConfig::new);
            config.setConfigKey(key);
            config.setConfigValue(value);
            if (config.getRemark() == null) config.setRemark("从备份还原");
            systemConfigRepository.save(config);
            count++;
        }
        return count;
    }

    private Device.DeviceType parseDeviceType(String type) {
        Device.DeviceType t = Device.DeviceType.fromValue(type);
        return t != null ? t : Device.DeviceType.other;
    }

    private String asString(Object value, String def) {
        if (value == null) return def;
        String s = String.valueOf(value);
        return s;
    }

    private int asInteger(Object value, int def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return def;
        }
    }

    private boolean asBoolean(Object value, boolean def) {
        if (value == null) return def;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return def;
    }

    private List<Object> asList(Object obj) {
        if (obj instanceof List<?> list) return new ArrayList<>(list);
        return Collections.emptyList();
    }

    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Collections.emptyMap();
    }
}
