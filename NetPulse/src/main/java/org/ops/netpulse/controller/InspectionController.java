package org.ops.netpulse.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.ops.netpulse.dto.InspectionReportDetailDto;
import org.ops.netpulse.dto.InspectionReportSummary;
import org.ops.netpulse.entity.InspectionReport;
import org.ops.netpulse.security.AuthTokenInterceptor;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.InspectionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统巡检：批量可达性探测并生成报告；列表为摘要投影，详情含每台设备 RTT 与状态。
 * <p>同一逻辑拆成两条映射（/inspection/** 与 /api/inspection/**），避免 @GetMapping({a,b}) 在部分环境下只注册一条导致仍落静态资源。
 */
@RestController
@RequiredArgsConstructor
@CrossOrigin
public class InspectionController {

    private final InspectionService inspectionService;
    private final AuditService auditService;

    @GetMapping("/inspection/run")
    public ResponseEntity<Map<String, String>> runGetNotAllowed() {
        return runGetNotAllowedBody();
    }

    @GetMapping("/api/inspection/run")
    public ResponseEntity<Map<String, String>> runGetNotAllowedApi() {
        return runGetNotAllowedBody();
    }

    private ResponseEntity<Map<String, String>> runGetNotAllowedBody() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("message", "请使用 POST 方法调用此接口", "path", "/api/inspection/run"));
    }

    @PostMapping("/inspection/run")
    public ResponseEntity<InspectionReport> run(@RequestBody(required = false) RunInspectionRequest body, HttpServletRequest req) {
        return runInspection(body, req);
    }

    @PostMapping("/api/inspection/run")
    public ResponseEntity<InspectionReport> runApi(@RequestBody(required = false) RunInspectionRequest body, HttpServletRequest req) {
        return runInspection(body, req);
    }

    private ResponseEntity<InspectionReport> runInspection(RunInspectionRequest body, HttpServletRequest req) {
        String group = body != null ? body.getGroup() : null;
        InspectionReport report = inspectionService.runInspection(group);
        boolean withAi = Boolean.TRUE.equals(body != null ? body.getAi() : null);
        auditService.log("RUN_INSPECTION", "inspection", report.getId(),
                "mode=manual,group=" + (group != null && !group.isBlank() ? group : "全部")
                        + ",total=" + report.getTotalCount() + ",ai=" + withAi);
        if (withAi) {
            String user = getUsername(req);
            inspectionService.generateAiSummary(report.getId(), user);
            report = inspectionService.findReportWithItems(report.getId()).orElse(report);
            auditService.log("AI_INSPECTION", "inspection", report.getId(), "mode=manual,via=run");
        }
        return ResponseEntity.ok(report);
    }

    private static String getUsername(HttpServletRequest req) {
        Object authUser = req.getAttribute(AuthTokenInterceptor.AUTH_USER_ATTR);
        if (authUser instanceof String s && !s.isBlank()) return s;
        String u = req.getHeader("X-User-Name");
        return u != null && !u.isEmpty() ? u : "匿名";
    }

    @GetMapping("/inspection/reports")
    public Page<InspectionReportSummary> listReports(
            @RequestParam(required = false) String source,
            @PageableDefault(size = 20) Pageable pageable) {
        return inspectionService.listReports(source, pageable);
    }

    @GetMapping("/api/inspection/reports")
    public Page<InspectionReportSummary> listReportsApi(
            @RequestParam(required = false) String source,
            @PageableDefault(size = 20) Pageable pageable) {
        return inspectionService.listReports(source, pageable);
    }

    @GetMapping("/inspection/reports/{id}")
    public ResponseEntity<InspectionReportDetailDto> getReport(@PathVariable Long id) {
        return getReportBody(id);
    }

    @GetMapping("/api/inspection/reports/{id}")
    public ResponseEntity<InspectionReportDetailDto> getReportApi(@PathVariable Long id) {
        return getReportBody(id);
    }

    private ResponseEntity<InspectionReportDetailDto> getReportBody(Long id) {
        return inspectionService.getReportDetail(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/inspection/ai-summary")
    public ResponseEntity<InspectionReportDetailDto> generateAiSummaryByBody(
            @RequestBody(required = false) AiSummaryRequest body,
            HttpServletRequest req) {
        Long id = body != null ? body.getReportId() : null;
        if (id == null) {
            return ResponseEntity.badRequest().build();
        }
        return generateAiSummaryBody(id, req);
    }

    @PostMapping("/api/inspection/ai-summary")
    public ResponseEntity<InspectionReportDetailDto> generateAiSummaryByBodyApi(
            @RequestBody(required = false) AiSummaryRequest body,
            HttpServletRequest req) {
        return generateAiSummaryByBody(body, req);
    }

    @PostMapping("/inspection/reports/{id}/ai-summary")
    public ResponseEntity<InspectionReportDetailDto> generateAiSummary(@PathVariable Long id, HttpServletRequest req) {
        return generateAiSummaryBody(id, req);
    }

    @PostMapping("/api/inspection/reports/{id}/ai-summary")
    public ResponseEntity<InspectionReportDetailDto> generateAiSummaryApi(@PathVariable Long id, HttpServletRequest req) {
        return generateAiSummaryBody(id, req);
    }

    private ResponseEntity<InspectionReportDetailDto> generateAiSummaryBody(Long id, HttpServletRequest req) {
        java.util.Optional<InspectionReportDetailDto> opt = inspectionService.generateAiSummary(id, getUsername(req));
        if (opt.isPresent()) {
            auditService.log("AI_INSPECTION", "inspection", id, "via=/inspection/ai-summary");
        }
        return opt.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/inspection/reports/{id}")
    public ResponseEntity<Void> deleteReport(@PathVariable Long id) {
        return deleteReportBody(id);
    }

    @DeleteMapping("/api/inspection/reports/{id}")
    public ResponseEntity<Void> deleteReportApi(@PathVariable Long id) {
        return deleteReportBody(id);
    }

    private ResponseEntity<Void> deleteReportBody(Long id) {
        if (!inspectionService.deleteReport(id)) {
            return ResponseEntity.notFound().build();
        }
        auditService.log("DELETE_INSPECTION", "inspection", id, "");
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class RunInspectionRequest {
        /** 设备分组名，空或省略表示全部未删除设备 */
        private String group;
        /** 为 true 时在探测完成后调用千问/DeepSeek 生成 AI 巡检结论并写入报告 */
        private Boolean ai;
    }

    @Data
    public static class AiSummaryRequest {
        /** 巡检报告主键 */
        private Long reportId;
    }
}
