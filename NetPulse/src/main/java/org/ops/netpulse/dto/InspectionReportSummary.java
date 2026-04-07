package org.ops.netpulse.dto;

import java.time.LocalDateTime;

/** 巡检报告列表行（不含明细，避免懒加载与序列化问题） */
public interface InspectionReportSummary {
    Long getId();

    LocalDateTime getCreatedAt();

    LocalDateTime getFinishedAt();

    String getGroupName();

    int getTotalCount();

    int getOkCount();

    int getWarnCount();

    int getOfflineCount();

    Long getDurationMs();

    String getSource();

    String getScheduleLabel();

    /** 是否已生成 AI 结论（列表展示用，可为 null） */
    String getAiSummary();
}
