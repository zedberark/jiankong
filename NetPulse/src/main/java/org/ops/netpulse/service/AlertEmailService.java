package org.ops.netpulse.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.SysUser;
import org.ops.netpulse.repository.AlertRuleRepository;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.repository.SysUserRepository;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 告警邮箱通知：从 system_config 读取 SMTP 与收件配置，告警触发时异步发邮件。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertEmailService {

    private static final String PREFIX = "alert.notify.";
    private static final String KEY_EMAIL_ENABLED = PREFIX + "email_enabled";
    private static final String KEY_SMTP_HOST = PREFIX + "smtp_host";
    private static final String KEY_SMTP_PORT = PREFIX + "smtp_port";
    private static final String KEY_SMTP_USER = PREFIX + "smtp_user";
    private static final String KEY_SMTP_PASSWORD = PREFIX + "smtp_password";
    private static final String KEY_FROM_EMAIL = PREFIX + "from_email";
    private static final String KEY_USER_NOTIFY_LEVELS_PREFIX = "alert.user.notify_levels.";

    private final SystemConfigService configService;
    private final AlertRuleRepository alertRuleRepository;
    private final DeviceRepository deviceRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    /**
     * 获取告警通知（邮箱）配置，供前端展示与保存。
     */
    public Map<String, String> getNotifySettings() {
        Map<String, String> out = new HashMap<>();
        out.put("emailEnabled", configService.getValue(KEY_EMAIL_ENABLED).orElse("0"));
        out.put("smtpHost", configService.getValue(KEY_SMTP_HOST).orElse(""));
        out.put("smtpPort", configService.getValue(KEY_SMTP_PORT).orElse("465"));
        out.put("smtpUser", configService.getValue(KEY_SMTP_USER).orElse(""));
        out.put("smtpPassword", configService.getValue(KEY_SMTP_PASSWORD).orElse(""));
        out.put("fromEmail", configService.getValue(KEY_FROM_EMAIL).orElse(""));
        return out;
    }

    /**
     * 保存告警通知配置（邮箱 + SMTP）。
     */
    public void saveNotifySettings(Map<String, Object> body) {
        if (body.get("emailEnabled") != null) {
            configService.saveByKey(KEY_EMAIL_ENABLED, "1".equals(String.valueOf(body.get("emailEnabled"))) ? "1" : "0");
        }
        if (body.get("smtpHost") != null) {
            configService.saveByKey(KEY_SMTP_HOST, String.valueOf(body.get("smtpHost")).trim());
        }
        if (body.get("smtpPort") != null) {
            configService.saveByKey(KEY_SMTP_PORT, String.valueOf(body.get("smtpPort")).trim());
        }
        if (body.get("smtpUser") != null) {
            configService.saveByKey(KEY_SMTP_USER, String.valueOf(body.get("smtpUser")).trim());
        }
        if (body.get("smtpPassword") != null) {
            configService.saveByKey(KEY_SMTP_PASSWORD, String.valueOf(body.get("smtpPassword")).trim());
        }
        if (body.get("fromEmail") != null) {
            configService.saveByKey(KEY_FROM_EMAIL, String.valueOf(body.get("fromEmail")).trim());
        }
    }

    /**
     * 告警产生时异步发送邮件。仅当该规则勾选了「邮件通知」且系统设置中已启用并配置邮箱时发送（不限于一级）。
     */
    @Async
    public void sendAlertEmailAsync(AlertHistory history) {
        if (history == null || history.getId() == null) return;
        AlertRule rule = history.getRuleId() != null ? alertRuleRepository.findById(history.getRuleId()).orElse(null) : null;
        if (rule == null || !Boolean.TRUE.equals(rule.getNotifyEmail())) {
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_SKIPPED", "alert_history", history.getId(),
                    "未发送邮件：规则未开启邮件通知");
            return;
        }
        if (!"1".equals(configService.getValue(KEY_EMAIL_ENABLED).orElse("0"))) {
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_SKIPPED", "alert_history", history.getId(),
                    "未发送邮件：系统未启用邮件通知");
            return;
        }
        String to = resolveRecipients(history);
        if (to == null || to.isEmpty()) {
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_SKIPPED", "alert_history", history.getId(),
                    "未发送邮件：无匹配收件人");
            return;
        }
        String host = configService.getValue(KEY_SMTP_HOST).map(String::trim).orElse(null);
        if (host == null || host.isEmpty()) {
            log.warn("告警邮件未发送：未配置 SMTP 主机");
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_FAILED", "alert_history", history.getId(),
                    "发送失败：未配置 SMTP 主机");
            return;
        }
        Device device = history.getDeviceId() != null ? deviceRepository.findById(history.getDeviceId()).orElse(null) : null;
        String subject = "[监控运维系统告警] " + (rule != null ? rule.getName() : "规则") + " - " + severityToChinese(history.getSeverity());
        String body = buildEmailBody(history, rule, device);

        try {
            JavaMailSenderImpl sender = createMailSender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String from = configService.getValue(KEY_FROM_EMAIL).map(String::trim).orElse(sender.getUsername());
            if (from != null && !from.isEmpty()) {
                helper.setFrom(from);
            }
            for (String addr : to.split("[,;\\s]+")) {
                String a = addr.trim();
                if (!a.isEmpty()) helper.addTo(a);
            }
            helper.setSubject(subject);
            helper.setText(body, true);
            sender.send(message);
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_SENT", "alert_history", history.getId(),
                    String.format("发送成功：to=%s, host=%s, ruleId=%s", to, host, history.getRuleId()));
            log.info("告警邮件已发送 historyId={} to={}", history.getId(), to);
        } catch (Exception e) {
            // 捕获所有发邮件异常（含 MailSendException、ConnectException 等），仅打日志不抛出，避免影响告警主流程
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("告警邮件发送失败 historyId={} host={}: {}。请检查「系统设置-告警通知」SMTP 地址（如 163 应为 smtp.163.com）、端口、授权码及网络。", history.getId(), host, msg);
            auditService.logAs("SYSTEM_ALERT", "ALERT_EMAIL_FAILED", "alert_history", history.getId(),
                    String.format("发送失败：host=%s, reason=%s", host, msg));
        }
    }

    /** 收件人来自“用户管理-告警通知级别”勾选。 */
    private String resolveRecipients(AlertHistory history) {
        if (history == null || history.getSeverity() == null) return null;
        String level = history.getSeverity().name().toLowerCase();
        List<SysUser> users = userRepository.findByEnabledTrue();
        if (users == null || users.isEmpty()) return null;
        Set<String> emails = new LinkedHashSet<>();
        for (SysUser u : users) {
            String email = u.getEmail() != null ? u.getEmail().trim() : "";
            if (email.isEmpty()) continue;
            String levels = configService.getValue(KEY_USER_NOTIFY_LEVELS_PREFIX + u.getId()).orElse("");
            if (containsLevel(levels, level)) {
                emails.add(email);
            }
        }
        return emails.isEmpty() ? null : String.join(",", emails);
    }

    private boolean containsLevel(String csv, String level) {
        if (csv == null || csv.isBlank() || level == null || level.isBlank()) return false;
        for (String part : csv.split("[,\\s]+")) {
            if (level.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    private String buildEmailBody(AlertHistory history, AlertRule rule, Device device) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>告警消息：</strong> ").append(history.getMessage() != null ? escapeHtml(history.getMessage()) : "-").append("</p>");
        sb.append("<p><strong>规则：</strong> ").append(rule != null ? escapeHtml(rule.getName()) : "-").append("</p>");
        sb.append("<p><strong>设备：</strong> ").append(device != null ? escapeHtml(device.getName() + " (" + device.getIp() + ")") : history.getDeviceId()).append("</p>");
        sb.append("<p><strong>严重程度：</strong> ").append(severityToChinese(history.getSeverity())).append("</p>");
        sb.append("<p><strong>时间：</strong> ").append(history.getStartTime() != null ? history.getStartTime().toString() : "-").append("</p>");
        return "<html><body>" + sb + "</body></html>";
    }

    private static String severityToChinese(AlertRule.Severity severity) {
        if (severity == null) return "-";
        return switch (severity) {
            case critical -> "一级·严重";
            case warning -> "二级·警告";
            case info -> "三级·一般/提示";
        };
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private JavaMailSenderImpl createMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(configService.getValue(KEY_SMTP_HOST).orElse(""));
        String portStr = configService.getValue(KEY_SMTP_PORT).orElse("465");
        try {
            sender.setPort(Integer.parseInt(portStr));
        } catch (NumberFormatException e) {
            sender.setPort(465);
        }
        sender.setUsername(configService.getValue(KEY_SMTP_USER).orElse(null));
        sender.setPassword(configService.getValue(KEY_SMTP_PASSWORD).orElse(null));
        Properties props = sender.getJavaMailProperties();
        if (props == null) props = new Properties();
        int port = sender.getPort();
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", "465");
        } else if (port == 587) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }
}
