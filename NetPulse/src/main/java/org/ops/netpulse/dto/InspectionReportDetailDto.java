package org.ops.netpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InspectionReportDetailDto {
    private Long id;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private String groupName;
    private int totalCount;
    private int okCount;
    private int warnCount;
    private int offlineCount;
    private Long durationMs;
    /** MANUAL / HOURLY / DAILY_00 / … */
    private String source;
    private String scheduleLabel;
    /** AI 巡检结论（Markdown），未生成时为 null */
    private String aiSummary;
    private List<InspectionItemRow> items;
}
