package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AuditLog;
import org.ops.netpulse.repository.AlertHistoryRepository;
import org.ops.netpulse.repository.AuditLogRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** 报表导出：告警历史、审计日志 CSV */
@RestController
@RequestMapping("/export")
@RequiredArgsConstructor
@CrossOrigin
public class ExportController {

    private final AlertHistoryRepository alertHistoryRepository;
    private final AuditLogRepository auditLogRepository;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping(value = "/alert-history", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportAlertHistory(@RequestParam(defaultValue = "500") int size) {
        List<AlertHistory> list = alertHistoryRepository.findByOrderByStatusAscStartTimeDesc(org.springframework.data.domain.PageRequest.of(0, Math.min(size, 2000))).getContent();
        String csv = "规则ID,设备ID,指标,触发值,开始时间,结束时间,状态,严重程度,消息\n" +
                list.stream().map(h -> h.getRuleId() + "," + h.getDeviceId() + "," + escapeCsv(h.getMetricKey()) + "," + escapeCsv(h.getTriggerValue()) + "," + (h.getStartTime() != null ? h.getStartTime().format(DF) : "") + "," + (h.getEndTime() != null ? h.getEndTime().format(DF) : "") + "," + (h.getStatus() != null ? h.getStatus().name() : "") + "," + (h.getSeverity() != null ? h.getSeverity().name() : "") + "," + escapeCsv(h.getMessage())).collect(Collectors.joining("\n"));
        return csvResponse("alert-history.csv", csv);
    }

    @GetMapping(value = "/audit-log", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportAuditLog(
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "500") int size) {
        org.springframework.data.domain.Page<AuditLog> page = auditLogRepository.findFiltered(username, null, org.springframework.data.domain.PageRequest.of(0, Math.min(size, 2000)));
        List<AuditLog> list = page.getContent();
        String csv = "时间,操作人,操作类型,对象类型,对象ID,详情,IP\n" +
                list.stream().map(a -> (a.getCreateTime() != null ? a.getCreateTime().format(DF) : "") + "," + escapeCsv(a.getUsername()) + "," + escapeCsv(a.getAction()) + "," + escapeCsv(a.getTargetType()) + "," + (a.getTargetId() != null ? a.getTargetId() : "") + "," + escapeCsv(a.getDetail()) + "," + escapeCsv(a.getIp())).collect(Collectors.joining("\n"));
        return csvResponse("audit-log.csv", csv);
    }

    private ResponseEntity<byte[]> csvResponse(String filename, String csv) {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
