package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "metric_key", nullable = false, length = 50)
    private String metricKey;

    @Column(name = "rule_condition", nullable = false, length = 255)
    private String condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('info','warning','critical')")
    private AlertRule.Severity severity = AlertRule.Severity.warning;

    /** 设备类型过滤：空或 null 表示全选；否则逗号分隔，如 server,firewall，仅匹配这些类型 */
    @Column(name = "device_types", length = 64)
    private String deviceTypes;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

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

