package org.ops.netpulse.repository;

import org.ops.netpulse.dto.InspectionReportSummary;
import org.ops.netpulse.entity.InspectionReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InspectionReportRepository extends JpaRepository<InspectionReport, Long> {

    Page<InspectionReportSummary> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<InspectionReportSummary> findBySourceOrderByCreatedAtDesc(String source, Pageable pageable);

    @Query("SELECT DISTINCT r FROM InspectionReport r LEFT JOIN FETCH r.items WHERE r.id = :id")
    Optional<InspectionReport> findByIdWithItems(@Param("id") Long id);
}
