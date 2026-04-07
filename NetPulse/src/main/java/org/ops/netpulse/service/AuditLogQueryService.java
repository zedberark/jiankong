package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.AuditLog;
import org.ops.netpulse.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;

    public Page<AuditLog> findFiltered(String username, String action, Pageable pageable) {
        String u = (username != null && !username.isBlank()) ? username.trim() : null;
        String a = (action != null && !action.isBlank()) ? action.trim() : null;
        return auditLogRepository.findFiltered(u, a, pageable);
    }
}
