package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.AuditLog;
import org.ops.netpulse.service.AuditLogQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@CrossOrigin
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @GetMapping
    public Page<AuditLog> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page, Math.min(size, 100));
        return auditLogQueryService.findFiltered(username, action, p);
    }
}
