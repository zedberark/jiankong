package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "config_backup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigBackup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "backup_type", nullable = false, length = 32)
    private String backupType = "full";

    /** 简要说明，如：设备 5 条, 告警 2 条, 配置 10 项 */
    @Column(length = 200)
    private String summary;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        if (createTime == null) createTime = LocalDateTime.now();
    }
}
