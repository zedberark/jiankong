package org.ops.netpulse.repository;

import org.ops.netpulse.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByEnabledTrue();

    List<AlertRule> findByDeviceIdAndEnabledTrue(Long deviceId);

    List<AlertRule> findByMetricKeyAndEnabledTrue(String metricKey);
}
