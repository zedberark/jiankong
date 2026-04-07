package org.ops.netpulse.repository;

import org.ops.netpulse.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE (:username IS NULL OR a.username LIKE CONCAT('%', :username, '%')) AND (:action IS NULL OR a.action LIKE CONCAT('%', :action, '%')) ORDER BY a.createTime DESC")
    Page<AuditLog> findFiltered(@Param("username") String username, @Param("action") String action, Pageable pageable);
}
