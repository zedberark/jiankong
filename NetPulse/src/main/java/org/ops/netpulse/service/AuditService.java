package org.ops.netpulse.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.AuditLog;
import org.ops.netpulse.security.AuthTokenInterceptor;
import org.ops.netpulse.repository.AuditLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** 记录操作审计（谁在什么时候做了什么）。在调用线程里先取当前用户与 IP，再异步落库，避免 @Async 线程拿不到请求上下文导致操作人变成匿名 */
    public void log(String action, String targetType, Long targetId, String detail) {
        String username = "匿名";
        String ip = null;
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            if (req != null) {
                ip = req.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty()) ip = req.getRemoteAddr();
                Object authUser = req.getAttribute(AuthTokenInterceptor.AUTH_USER_ATTR);
                String u = authUser instanceof String s ? s : req.getHeader("X-User-Name");
                if (u != null && !u.isEmpty()) username = u;
            }
        }
        saveAsync(username, ip, action, targetType, targetId, detail);
    }

    /**
     * 指定操作人写入审计（无 HTTP 请求上下文，如定时任务），IP 为空。
     *
     * @param username 操作人，如 SYSTEM_SCHEDULER；空则记为「匿名」
     */
    public void logAs(String username, String action, String targetType, Long targetId, String detail) {
        String u = (username != null && !username.isBlank()) ? username.trim() : "匿名";
        saveAsync(u, null, action, targetType, targetId, detail);
    }

    @Async
    void saveAsync(String username, String ip, String action, String targetType, Long targetId, String detail) {
        try {
            AuditLog entry = AuditLog.builder()
                    .username(username)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail != null && detail.length() > 2000 ? detail.substring(0, 2000) : detail)
                    .ip(ip)
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Audit log failed: {}", e.getMessage());
        }
    }
}
