package org.ops.netpulse.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "inspection_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InspectionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "group_name", length = 128)
    private String groupName;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "ok_count", nullable = false)
    private int okCount;

    @Column(name = "warn_count", nullable = false)
    private int warnCount;

    @Column(name = "offline_count", nullable = false)
    private int offlineCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** MANUAL / HOURLY / DAILY_00 / DAILY_18 / WEEKLY_MON / WEEKLY_SUN */
    @Column(name = "source", nullable = false, length = 32)
    @Builder.Default
    private String source = "MANUAL";

    /** 展示用，如「整点巡检」「日报（零点）」 */
    @Column(name = "schedule_label", length = 128)
    private String scheduleLabel;

    /** 千问/DeepSeek 根据本次探测结果生成的运维结论（Markdown） */
    @Column(name = "ai_summary", columnDefinition = "LONGTEXT")
    private String aiSummary;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InspectionItem> items = new ArrayList<>();
}
