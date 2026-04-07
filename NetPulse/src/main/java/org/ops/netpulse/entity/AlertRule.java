package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "device_id")
    private Long deviceId;

    /** 设备类型过滤：空或 null 表示全选；否则逗号分隔，如 server,firewall，仅匹配这些类型 */
    @Column(name = "device_types", length = 64)
    private String deviceTypes;

    @Column(name = "metric_key", nullable = false, length = 50)
    private String metricKey;

    /** 告警条件表达式；避免 MySQL 保留字 condition，列名用 rule_condition */
    @Column(name = "rule_condition", nullable = false, length = 255)
    private String condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('info','warning','critical')")
    private Severity severity = Severity.warning;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 该规则触发时是否发送邮件（需在系统设置中启用并配置邮箱） */
    @Column(name = "notify_email")
    private Boolean notifyEmail = false;

    /** 告警触发时是否自动执行修复脚本 */
    @Column(name = "auto_fix_enabled")
    private Boolean autoFixEnabled = false;

    /** 修复方式：ssh_command=在告警设备上执行 SSH 命令；local_script=在服务器本地执行脚本路径 */
    @Column(name = "auto_fix_type", length = 32)
    private String autoFixType = "ssh_command";

    /** 修复命令或脚本：ssh_command 时为在设备上执行的 Shell 命令；local_script 时为服务器本地脚本路径 */
    @Column(name = "auto_fix_command", columnDefinition = "TEXT")
    private String autoFixCommand;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public enum Severity { info, warning, critical }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
