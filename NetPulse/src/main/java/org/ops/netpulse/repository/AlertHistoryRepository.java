package org.ops.netpulse.repository;

import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    List<AlertHistory> findByDeviceIdOrderByStartTimeDesc(Long deviceId, Pageable pageable);

    List<AlertHistory> findByStatus(AlertHistory.AlertStatus status);

    /** 告警历史列表：未处理(firing)排前、已处理(resolved)排后，同组内按时间倒序 */
    Page<AlertHistory> findByOrderByStatusAscStartTimeDesc(Pageable pageable);

    Page<AlertHistory> findByStatusOrderByStartTimeDesc(AlertHistory.AlertStatus status, Pageable pageable);

    /** 按严重程度筛选时：未处理在前、已处理在后，同组内按时间倒序 */
    Page<AlertHistory> findBySeverityOrderByStatusAscStartTimeDesc(AlertRule.Severity severity, Pageable pageable);

    Page<AlertHistory> findByStatusAndSeverityOrderByStartTimeDesc(
            AlertHistory.AlertStatus status, AlertRule.Severity severity, Pageable pageable);

    /** 查某规则某设备下最近一条指定状态的告警（用于指标类规则：是否已有 firing / 需 resolve） */
    Optional<AlertHistory> findTopByRuleIdAndDeviceIdAndStatusOrderByStartTimeDesc(
            Long ruleId, Long deviceId, AlertHistory.AlertStatus status);

    /** 最近的告警（按时间倒序），用于首页告警概览 */
    List<AlertHistory> findBySeverityInAndStatusOrderByStartTimeDesc(
            List<AlertRule.Severity> severities,
            AlertHistory.AlertStatus status,
            Pageable pageable);

    /** 当前处于某状态的各等级告警数量，用于首页饼图 */
    long countByStatusAndSeverity(AlertHistory.AlertStatus status, AlertRule.Severity severity);

    /** 按设备 ID 范围统计：当前处于某状态的各等级告警数量（用于分组维度） */
    long countByStatusAndSeverityAndDeviceIdIn(AlertHistory.AlertStatus status, AlertRule.Severity severity, List<Long> deviceIds);

    /** 按设备 ID 范围查询最近告警（用于分组维度） */
    List<AlertHistory> findBySeverityInAndStatusAndDeviceIdInOrderByStartTimeDesc(
            List<AlertRule.Severity> severities,
            AlertHistory.AlertStatus status,
            List<Long> deviceIds,
            Pageable pageable);

    long countByStartTimeGreaterThanEqual(LocalDateTime startTime);

    long countByStartTimeGreaterThanEqualAndSeverity(LocalDateTime startTime, AlertRule.Severity severity);

    long countByStartTimeGreaterThanEqualAndStatus(LocalDateTime startTime, AlertHistory.AlertStatus status);

    List<AlertHistory> findTop10ByStartTimeGreaterThanEqualOrderByStartTimeDesc(LocalDateTime startTime);
}
