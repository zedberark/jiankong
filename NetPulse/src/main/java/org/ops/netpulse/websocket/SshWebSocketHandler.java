package org.ops.netpulse.websocket;

import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.DeviceSshCollectService;
import org.ops.netpulse.service.WebSshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class SshWebSocketHandler extends AbstractWebSocketHandler {

    private final WebSshService webSshService;
    private final AuditService auditService;
    private final DeviceRepository deviceRepository;
    private final Map<String, WebSshService.ShellSessionHolder> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionToDeviceId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionIsLinuxDevice = new ConcurrentHashMap<>();
    /** 输入缓冲：按会话聚合键盘输入，遇到回车后切分出一条命令写入审计 */
    private final Map<String, StringBuilder> sessionInputBuffers = new ConcurrentHashMap<>();
    /** 安全缓冲：用于命令拦截判断，避免影响审计缓冲状态。 */
    private final Map<String, StringBuilder> sessionSecurityInputBuffers = new ConcurrentHashMap<>();
    /** 会话对应的设备输出缓冲，用于在 Web SSH 中执行 show version/cpu/memory 时自动解析并写入设备指标缓存 */
    private final Map<String, StringBuilder> sessionOutputBuffers = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionLastIngestTime = new ConcurrentHashMap<>();
    private static final int INGEST_MIN_LENGTH = 1500;
    private static final long INGEST_THROTTLE_MS = 4000;
    /** 终端输出包含这些关键字时，哪怕总长度较短也立即尝试解析写入设备指标。 */
    private static final String[] QUICK_INGEST_KEYWORDS = new String[] {
            "memory using percentage",
            "used ratio for memory",
            "cpu utilization for five seconds",
            "cpu usage",
            "used rate",
            "processor",
            "system total memory is",
            "total memory used is"
    };

    @Autowired(required = false)
    private DeviceSshCollectService deviceSshCollectService;

    public SshWebSocketHandler(WebSshService webSshService, AuditService auditService, DeviceRepository deviceRepository) {
        this.webSshService = webSshService;
        this.auditService = auditService;
        this.deviceRepository = deviceRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        String deviceIdStr = getDeviceId(wsSession);
        if (deviceIdStr == null) {
            wsSession.close(CloseStatus.BAD_DATA);
            return;
        }
        Long deviceId;
        try {
            deviceId = Long.valueOf(deviceIdStr);
        } catch (NumberFormatException e) {
            wsSession.close(CloseStatus.BAD_DATA);
            return;
        }
        sessionToDeviceId.put(wsSession.getId(), deviceId);
        sessionInputBuffers.put(wsSession.getId(), new StringBuilder());
        sessionSecurityInputBuffers.put(wsSession.getId(), new StringBuilder());
        sessionIsLinuxDevice.put(wsSession.getId(), isLinuxDevice(deviceId));
        auditService.log("WEBSH_CONNECT", "device", deviceId, "建立 WebSSH 会话");
        // JSch 在独立线程写设备输出；必须用装饰器保证 sendMessage 线程安全，否则 Tomcat 上易立即断连
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(wsSession, 30_000, 512 * 1024);
        OutputStream out = new DeviceOutputForwardStream(outbound, wsSession.getId(), deviceId, this);
        Optional<WebSshService.ShellSessionHolder> holderOpt;
        try {
            holderOpt = webSshService.createSession(deviceId, out);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "设备未配置或不可达";
            log.warn("Web 终端连接失败 deviceId={}: {}", deviceId, msg);
            // 仅当是连接/网络类失败时标记「曾连不上」，未配置用户名密码不标记，避免误伤
            boolean isConfigMissing = msg.contains("未配置") || msg.contains("用户名或密码") || msg.contains("不存在或已删除");
            if (deviceSshCollectService != null && !isConfigMissing) deviceSshCollectService.markWebSshUnreachable(deviceId);
            wsSession.sendMessage(new TextMessage("{\"error\":\"连接失败: " + jsonEscapeForInline(msg) + "\"}"));
            sessionToDeviceId.remove(wsSession.getId());
            wsSession.close(CloseStatus.BAD_DATA);
            return;
        }
        if (holderOpt.isEmpty()) {
            wsSession.sendMessage(new TextMessage("{\"error\":\"SSH/Telnet 连接失败或设备未配置用户名密码\"}"));
            sessionToDeviceId.remove(wsSession.getId());
            wsSession.close(CloseStatus.BAD_DATA);
            return;
        }
        WebSshService.ShellSessionHolder holder = holderOpt.get();
        sessionMap.put(wsSession.getId(), holder);
        if (deviceSshCollectService != null) deviceSshCollectService.markWebSshReachable(deviceId);
    }

    /** 异常信息写入单行 JSON 字符串：必须去掉换行并转义，否则前端解析失败且可能触发 WebSocket 异常关闭(1007)。 */
    private static String jsonEscapeForInline(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static final int INGEST_BUFFER_MAX_LENGTH = 60_000;

    /** 设备输出写入前端的同时，累积并定期解析写入设备指标缓存（show version / show cpu / show memory 等） */
    void onDeviceOutput(String sessionId, Long deviceId, String chunk) {
        if (deviceSshCollectService == null || chunk == null || chunk.isEmpty()) return;
        StringBuilder buf = sessionOutputBuffers.computeIfAbsent(sessionId, k -> new StringBuilder());
        buf.append(chunk);
        if (buf.length() > INGEST_BUFFER_MAX_LENGTH) {
            buf.delete(0, buf.length() - INGEST_BUFFER_MAX_LENGTH);
        }
        boolean quickHit = containsQuickIngestSignal(chunk) || containsQuickIngestSignal(buf);
        if (!quickHit && buf.length() < INGEST_MIN_LENGTH) return;
        long now = System.currentTimeMillis();
        Long last = sessionLastIngestTime.get(sessionId);
        if (last != null && (now - last) < INGEST_THROTTLE_MS) return;
        sessionLastIngestTime.put(sessionId, now);
        try {
            deviceSshCollectService.ingestFromWebSshOutput(deviceId, buf.toString());
        } catch (Exception e) {
            log.trace("Web SSH 自动写入设备指标解析异常: {}", e.getMessage());
        }
    }

    /** 短输出（如 dis memory-usage）命中关键字时也触发指标解析。 */
    private static boolean containsQuickIngestSignal(CharSequence text) {
        if (text == null || text.length() == 0) return false;
        String lower = text.toString().toLowerCase();
        for (String k : QUICK_INGEST_KEYWORDS) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    private String getDeviceId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            if (param.startsWith("deviceId=")) {
                return param.substring("deviceId=".length()).trim();
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        WebSshService.ShellSessionHolder holder = sessionMap.get(wsSession.getId());
        if (holder != null) {
            if (shouldBlockDangerousCommand(wsSession, message.getPayload())) return;
            auditCommandInput(wsSession.getId(), message.getPayload());
            PipedOutputStream pipe = holder.getPipeOut();
            if (pipe != null) {
                pipe.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
                pipe.flush();
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession wsSession, BinaryMessage message) throws Exception {
        WebSshService.ShellSessionHolder holder = sessionMap.get(wsSession.getId());
        if (holder != null) {
            String payload = new String(message.getPayload().array(), StandardCharsets.UTF_8);
            if (shouldBlockDangerousCommand(wsSession, payload)) return;
            auditCommandInput(wsSession.getId(), payload);
            PipedOutputStream pipe = holder.getPipeOut();
            if (pipe != null) {
                pipe.write(message.getPayload().array());
                pipe.flush();
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        String sid = wsSession.getId();
        Long deviceId = sessionToDeviceId.get(sid);
        if (deviceId != null) {
            String reason = status != null ? status.getReason() : null;
            auditService.log("WEBSH_DISCONNECT", "device", deviceId,
                    "关闭 WebSSH 会话" + ((reason != null && !reason.isBlank()) ? ("，原因: " + reason) : ""));
        }
        sessionIsLinuxDevice.remove(sid);
        sessionInputBuffers.remove(sid);
        sessionSecurityInputBuffers.remove(sid);
        sessionOutputBuffers.remove(sid);
        sessionLastIngestTime.remove(sid);
        sessionToDeviceId.remove(sid);
        WebSshService.ShellSessionHolder holder = sessionMap.remove(sid);
        if (holder != null) {
            try {
                PipedOutputStream pipe = holder.getPipeOut();
                if (pipe != null) pipe.close();
            } catch (IOException ignored) {}
            webSshService.disconnect(holder);
        }
    }

    private void auditCommandInput(String sessionId, String payload) {
        if (payload == null || payload.isEmpty()) return;
        StringBuilder buf = sessionInputBuffers.computeIfAbsent(sessionId, k -> new StringBuilder());
        List<String> commands = extractCompletedCommands(buf, payload);
        if (commands.isEmpty()) return;
        Long deviceId = sessionToDeviceId.get(sessionId);
        if (deviceId == null) return;
        for (String command : commands) {
            String cmd = sanitizeCommand(command);
            if (cmd.isEmpty()) continue;
            auditService.log("WEBSH_COMMAND", "device", deviceId, "执行命令: " + cmd);
        }
    }

    /** 解析输入流中已完成的命令（以回车换行作为提交边界）。 */
    private static List<String> extractCompletedCommands(StringBuilder buf, String payload) {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < payload.length(); i++) {
            char ch = payload.charAt(i);
            if (ch == '\r' || ch == '\n') {
                if (buf.length() > 0) {
                    commands.add(buf.toString());
                    buf.setLength(0);
                }
                continue;
            }
            // 处理退格键，确保审计命令接近用户实际输入
            if (ch == '\b' || ch == 127) {
                if (buf.length() > 0) buf.deleteCharAt(buf.length() - 1);
                continue;
            }
            if (ch >= 32) buf.append(ch);
        }
        if (buf.length() > 2000) {
            buf.delete(0, buf.length() - 2000);
        }
        return commands;
    }

    /** 过滤控制字符与敏感命令内容，避免审计中出现明显密钥/口令。 */
    private static String sanitizeCommand(String command) {
        if (command == null) return "";
        String plain = command.replaceAll("[\\p{Cntrl}&&[^\t]]", "").trim();
        if (plain.isEmpty()) return "";
        String lower = plain.toLowerCase();
        if (lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("community") || lower.contains("private-key")) {
            return "[敏感命令已脱敏]";
        }
        return plain.length() > 500 ? plain.substring(0, 500) : plain;
    }

    private boolean isLinuxDevice(Long deviceId) {
        if (deviceId == null) return false;
        return deviceRepository.findById(deviceId)
                .filter(d -> !Boolean.TRUE.equals(d.getDeleted()))
                .map(Device::getType)
                .map(t -> t == Device.DeviceType.server)
                .orElse(false);
    }

    private boolean shouldBlockDangerousCommand(WebSocketSession wsSession, String payload) throws IOException {
        String sid = wsSession.getId();
        if (!Boolean.TRUE.equals(sessionIsLinuxDevice.get(sid))) return false;
        if (payload == null || payload.isEmpty()) return false;
        StringBuilder buf = sessionSecurityInputBuffers.computeIfAbsent(sid, k -> new StringBuilder());
        List<String> commands = extractCompletedCommands(buf, payload);
        Long deviceId = sessionToDeviceId.get(sid);
        for (String command : commands) {
            String normalized = command == null ? "" : command.replaceAll("\\s+", " ").trim();
            if (isForbiddenLinuxDeleteRoot(normalized)) {
                if (deviceId != null) {
                    auditService.log("WEBSH_COMMAND_BLOCKED", "device", deviceId,
                            "拦截高危命令: rm -rf /（Linux 设备安全策略）");
                }
                wsSession.sendMessage(new TextMessage("\r\n[安全拦截] 已禁止执行高危命令：rm -rf /\r\n"));
                return true;
            }
        }
        return false;
    }

    private static boolean isForbiddenLinuxDeleteRoot(String normalizedCommand) {
        if (normalizedCommand == null || normalizedCommand.isBlank()) return false;
        String lower = normalizedCommand.toLowerCase();
        if (!lower.startsWith("rm ") && !lower.startsWith("sudo rm ")) return false;
        boolean recursiveForce = lower.contains("-rf") || lower.contains("-fr")
                || (lower.contains("-r") && lower.contains("-f"));
        if (!recursiveForce) return false;
        return lower.contains(" /") || lower.endsWith("/") || lower.contains(" /*")
                || lower.contains(" --no-preserve-root /");
    }

    /** 将设备输出转发到 WebSocket，并通知 handler 累积以便解析写入设备指标 */
    private static class DeviceOutputForwardStream extends OutputStream {
        private final WebSocketSession session;
        private final String sessionId;
        private final Long deviceId;
        private final SshWebSocketHandler handler;

        DeviceOutputForwardStream(WebSocketSession session, String sessionId, Long deviceId, SshWebSocketHandler handler) {
            this.session = session;
            this.sessionId = sessionId;
            this.deviceId = deviceId;
            this.handler = handler;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!session.isOpen()) return;
            String chunk = new String(b, off, len, StandardCharsets.UTF_8);
            session.sendMessage(new TextMessage(chunk));
            handler.onDeviceOutput(sessionId, deviceId, chunk);
        }
    }
}
