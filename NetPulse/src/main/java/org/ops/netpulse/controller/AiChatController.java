package org.ops.netpulse.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.ops.netpulse.dto.InspectionReportDetailDto;
import org.ops.netpulse.entity.AiChatMessage;
import org.ops.netpulse.entity.AiChatSession;
import org.ops.netpulse.security.AuthTokenInterceptor;
import org.ops.netpulse.service.AiChatService;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.InspectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@CrossOrigin
public class AiChatController {

    private final AiChatService aiChatService;
    private final InspectionService inspectionService;
    private final AuditService auditService;

    private String getUsername(HttpServletRequest req) {
        Object authUser = req.getAttribute(AuthTokenInterceptor.AUTH_USER_ATTR);
        if (authUser instanceof String s && !s.isBlank()) return s;
        String u = req.getHeader("X-User-Name");
        return u != null && !u.isEmpty() ? u : "匿名";
    }

    @GetMapping("/sessions")
    public List<AiChatSession> listSessions(HttpServletRequest req) {
        return aiChatService.getSessions(getUsername(req));
    }

    @GetMapping("/sessions/{id}/messages")
    public List<AiChatMessage> getMessages(@PathVariable Long id, HttpServletRequest req) {
        return aiChatService.getMessages(id, getUsername(req));
    }

    @PostMapping("/sessions")
    public AiChatSession newSession(HttpServletRequest req) {
        return aiChatService.createSession(getUsername(req));
    }

    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable Long id, HttpServletRequest req) {
        aiChatService.deleteSession(id, getUsername(req));
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest body, HttpServletRequest req) {
        String username = getUsername(req);
        boolean transientChat = Boolean.TRUE.equals(body.getTransientChat());
        AiChatService.ChatResult result = aiChatService.chat(body.getSessionId(), username, body.getMessage(), transientChat);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("reply", result.getReply());
        out.put("sessionId", result.getSessionId());
        return out;
    }

    /**
     * 巡检报告 AI 结论：与 /ai/chat 同前缀，避免部分网关将 /inspection/ai-summary 误判为静态资源。
     */
    @PostMapping("/inspection-summary")
    public ResponseEntity<InspectionReportDetailDto> inspectionSummary(
            @RequestBody(required = false) InspectionReportIdRequest body,
            HttpServletRequest req) {
        Long id = body != null ? body.getReportId() : null;
        if (id == null) {
            return ResponseEntity.badRequest().build();
        }
        java.util.Optional<InspectionReportDetailDto> opt = inspectionService.generateAiSummary(id, getUsername(req));
        if (opt.isPresent()) {
            auditService.log("AI_INSPECTION", "inspection", id, "via=/ai/inspection-summary");
        }
        return opt.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class InspectionReportIdRequest {
        private Long reportId;
    }

    @lombok.Data
    public static class ChatRequest {
        private Long sessionId;
        private String message;
        /** true 时不创建/写入会话，仅一次性调用并返回回复，用于网络 AI 命令、批量命令等，不进入 AI 运维助手会话列表 */
        private Boolean transientChat;
    }
}
