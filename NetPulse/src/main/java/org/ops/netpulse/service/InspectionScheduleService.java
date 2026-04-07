package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.InspectionReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 巡检报告定时生成（与手动巡检同一套探测逻辑，不写 Influx、不触发告警）：
 * <ul>
 *   <li>整点：每小时第 0 分第 0 秒</li>
 *   <li>日报：每天 0 点、18 点（Asia/Shanghai）</li>
 *   <li>周报：每周一 0 点、每周日 0 点</li>
 * </ul>
 * 周一 0 点可能与整点、日报重叠，会各自生成一条报告。
 * <p>默认 {@code inspection.schedule.ai-enabled=true}：整点/日报/周报每次探测完成后自动调用大模型生成 AI 结论（与手动勾选 AI 一致）。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InspectionScheduleService {

    private final InspectionService inspectionService;
    private final AuditService auditService;

    @Value("${inspection.schedule.enabled:true}")
    private boolean scheduleEnabled;

    @Value("${inspection.schedule.ai-enabled:true}")
    private boolean scheduleAiEnabled;

    private static final String SCHEDULER_AI_USER = "SYSTEM_SCHEDULER";

    /** 整点巡检 */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    public void hourly() {
        runScheduled("HOURLY", "整点巡检");
    }

    /** 日报：每天 0 点 */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void dailyMidnight() {
        runScheduled("DAILY_00", "日报（零点）");
    }

    /** 日报：每天 18 点 */
    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Shanghai")
    public void dailyEvening() {
        runScheduled("DAILY_18", "日报（18点）");
    }

    /** 周报：每周一 0 点 */
    @Scheduled(cron = "0 0 0 ? * MON", zone = "Asia/Shanghai")
    public void weeklyMonday() {
        runScheduled("WEEKLY_MON", "周报（周一）");
    }

    /** 周报：每周日 0 点 */
    @Scheduled(cron = "0 0 0 ? * SUN", zone = "Asia/Shanghai")
    public void weeklySunday() {
        runScheduled("WEEKLY_SUN", "周报（周日）");
    }

    private void runScheduled(String source, String scheduleLabel) {
        if (!scheduleEnabled) return;
        try {
            InspectionReport report = inspectionService.runInspection(null, source, scheduleLabel);
            if (report != null && report.getId() != null) {
                auditService.logAs(SCHEDULER_AI_USER, "RUN_INSPECTION", "inspection", report.getId(),
                        "mode=scheduled,source=" + source + ",label=" + scheduleLabel + ",total=" + report.getTotalCount());
                if (scheduleAiEnabled) {
                    try {
                        inspectionService.generateAiSummary(report.getId(), SCHEDULER_AI_USER);
                        auditService.logAs(SCHEDULER_AI_USER, "AI_INSPECTION", "inspection", report.getId(),
                                "mode=scheduled,source=" + source + ",label=" + scheduleLabel);
                    } catch (Exception ex) {
                        log.warn("定时巡检 AI 结论生成失败 reportId={}: {}", report.getId(), ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("定时巡检[{}]失败: {}", source, e.getMessage());
        }
    }
}
