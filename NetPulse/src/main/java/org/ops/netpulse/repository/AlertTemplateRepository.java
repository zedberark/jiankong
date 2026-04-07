package org.ops.netpulse.repository;

import org.ops.netpulse.entity.AlertTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertTemplateRepository extends JpaRepository<AlertTemplate, Long> {
}

