package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "metric_key", length = 50)
    private String metricKey;

    @Column(name = "trigger_value", length = 50)
    private String triggerValue;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('firing','resolved')")
    private AlertStatus status = AlertStatus.firing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('info','warning','critical')")
    private AlertRule.Severity severity;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public enum AlertStatus { firing, resolved }

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
