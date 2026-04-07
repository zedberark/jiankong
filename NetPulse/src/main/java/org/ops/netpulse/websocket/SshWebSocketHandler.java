package org.ops.netpulse.websocket;

import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.service.DeviceSshCollectService;
import org.ops.netpulse.service.WebSshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class SshWebSocketHandler extends AbstractWebSocketHandler {

    private final WebSshService webSshService;
    private final Map<String, WebSshService.ShellSessionHolder> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionToDeviceId = new ConcurrentHashMap<>();
    /** 会话对应的设备输出缓冲，用于在 Web SSH 中执行 show version/cpu/memory 时自动解析并写入设备指标缓存 */
    private final Map<String, StringBuilder> sessionOutputBuffers = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionLastIngestTime = new ConcurrentHashMap<>();
    private static final int INGEST_MIN_LENGTH = 1500;
    private static final long INGEST_THROTTLE_MS = 4000;

    @Autowired(required = false)
    private DeviceSshCollectService deviceSshCollectService;

    public SshWebSocketHandler(WebSshService webSshService) {
        this.webSshService = webSshService;
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
        OutputStream out = new DeviceOutputForwardStream(wsSession, wsSession.getId(), deviceId, this);
        Optional<WebSshService.ShellSessionHolder> holderOpt;
        try {
            holderOpt = webSshService.createSession(deviceId, out);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "设备未配置或不可达";
            log.warn("Web 终端连接失败 deviceId={}: {}", deviceId, msg);
            // 仅当是连接/网络类失败时标记「曾连不上」，未配置用户名密码不标记，避免误伤
            boolean isConfigMissing = msg.contains("未配置") || msg.contains("用户名或密码") || msg.contains("不存在或已删除");
            if (deviceSshCollectService != null && !isConfigMissing) deviceSshCollectService.markWebSshUnreachable(deviceId);
            wsSession.sendMessage(new TextMessage("{\"error\":\"连接失败: " + msg.replace("\"", "\\\"") + "\"}"));
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

    private static final int INGEST_BUFFER_MAX_LENGTH = 60_000;

    /** 设备输出写入前端的同时，累积并定期解析写入设备指标缓存（show version / show cpu / show memory 等） */
    void onDeviceOutput(String sessionId, Long deviceId, String chunk) {
        if (deviceSshCollectService == null || chunk == null || chunk.isEmpty()) return;
        StringBuilder buf = sessionOutputBuffers.computeIfAbsent(sessionId, k -> new StringBuilder());
        buf.append(chunk);
        if (buf.length() > INGEST_BUFFER_MAX_LENGTH) {
            buf.delete(0, buf.length() - INGEST_BUFFER_MAX_LENGTH);
        }
        if (buf.length() < INGEST_MIN_LENGTH) return;
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
