package org.ops.netpulse.service;

import com.jcraft.jsch.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.telnet.TelnetClient;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class WebSshService {

    private static final int TELNET_PORT = 23;
    /** Telnet IAC NOP，用于保活，避免设备侧空闲断开会话 */
    private static final byte[] TELNET_IAC_NOP = new byte[] { (byte) 255, (byte) 241 };
    private static final int KEEPALIVE_SEC = 45;
    private static final ExecutorService telnetExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "telnet-io");
        t.setDaemon(true);
        return t;
    });

    private final DeviceRepository deviceRepository;
    private final int sshConnectTimeoutMs;
    private final int sshChannelConnectTimeoutMs;
    private final int telnetConnectTimeoutMs;
    private final int sshSessionSocketTimeoutMs;

    public WebSshService(
            DeviceRepository deviceRepository,
            @Value("${webssh.ssh-connect-timeout-ms:20000}") int sshConnectTimeoutMs,
            @Value("${webssh.ssh-channel-connect-timeout-ms:15000}") int sshChannelConnectTimeoutMs,
            @Value("${webssh.telnet-connect-timeout-ms:20000}") int telnetConnectTimeoutMs,
            @Value("${webssh.ssh-session-timeout-ms:30000}") int sshSessionSocketTimeoutMs) {
        this.deviceRepository = deviceRepository;
        this.sshConnectTimeoutMs = sshConnectTimeoutMs;
        this.sshChannelConnectTimeoutMs = sshChannelConnectTimeoutMs;
        this.telnetConnectTimeoutMs = telnetConnectTimeoutMs;
        this.sshSessionSocketTimeoutMs = sshSessionSocketTimeoutMs;
    }

    /**
     * 创建 Web 终端会话：端口 23 走 Telnet，否则走 SSH；输出到同一 Web 终端。
     * 设备不存在或未配置用户名/密码时抛出 IOException，便于前端提示到设备管理填写。
     */
    public Optional<ShellSessionHolder> createSession(Long deviceId, OutputStream out) throws Exception {
        Device device = deviceRepository.findById(deviceId).filter(d -> !Boolean.TRUE.equals(d.getDeleted())).orElse(null);
        if (device == null) {
            throw new IOException("设备不存在或已删除");
        }
        if (device.getSshUser() == null || device.getSshUser().isBlank()
                || device.getSshPassword() == null || device.getSshPassword().isBlank()) {
            log.warn("Web 终端连接失败 deviceId={}：未配置 SSH/Telnet 用户名或密码，请到设备管理编辑该设备并填写后保存", deviceId);
            throw new IOException("设备未配置 SSH/Telnet 用户名或密码，请到「设备管理」编辑该设备并填写后保存");
        }
        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        if (port == TELNET_PORT) {
            return createTelnetSession(device, out);
        }
        return createSshSession(device, port, out);
    }

    private Optional<ShellSessionHolder> createSshSession(Device device, int port, OutputStream out) throws JSchException, IOException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(device.getSshUser(), device.getIp(), port);
        session.setPassword(device.getSshPassword());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        // 保活：约 45 秒无数据时发 keepalive，避免设备侧空闲断开会话
        config.put("ServerAliveInterval", "45");
        config.put("ServerAliveCountMax", "3");
        session.setConfig(config);
        session.connect(sshConnectTimeoutMs);
        session.setTimeout(sshSessionSocketTimeoutMs);

        Channel channel = session.openChannel("shell");
        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        channel.setInputStream(pipeIn);
        channel.setOutputStream(out);
        channel.connect(sshChannelConnectTimeoutMs);

        return Optional.of(new SshSessionHolder(session, pipeOut));
    }

    private Optional<ShellSessionHolder> createTelnetSession(Device device, OutputStream out) throws IOException {
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(telnetConnectTimeoutMs);
        String ip = device.getIp();
        int port = device.getSshPort() != null ? device.getSshPort() : TELNET_PORT;
        try {
            telnet.connect(ip, port);
        } catch (IOException e) {
            log.warn("Telnet 连接失败 {}:{} - {}", ip, port, e.getMessage());
            throw new IOException("Telnet " + ip + ":" + port + " " + (e.getMessage() != null ? e.getMessage() : "连接超时或拒绝"), e);
        }

        InputStream telnetIn = telnet.getInputStream();
        OutputStream telnetOut = telnet.getOutputStream();
        String user = device.getSshUser() != null ? device.getSshUser() : "";
        String pass = device.getSshPassword() != null ? device.getSshPassword() : "";

        // 使用设备管理中的用户名密码自动登录，无需在终端再输入
        try {
            telnetDoLogin(telnetIn, telnetOut, out, user, pass);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Telnet 自动登录被中断", e);
        }

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

        // 设备输出 -> Web 终端
        telnetExecutor.submit(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = telnetIn.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                log.trace("Telnet 读线程结束: {}", e.getMessage());
            }
        });

        // 用户输入 -> 设备
        telnetExecutor.submit(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = pipeIn.read(buf)) != -1) {
                    telnetOut.write(buf, 0, n);
                    telnetOut.flush();
                }
            } catch (IOException e) {
                log.trace("Telnet 写线程结束: {}", e.getMessage());
            }
        });

        // 保活：定期发 Telnet IAC NOP，避免设备侧空闲断开会话
        AtomicBoolean closed = new AtomicBoolean(false);
        telnetExecutor.submit(() -> {
            try {
                while (!closed.get()) {
                    TimeUnit.SECONDS.sleep(KEEPALIVE_SEC);
                    if (closed.get()) break;
                    if (telnet.isConnected()) {
                        try {
                            telnetOut.write(TELNET_IAC_NOP);
                            telnetOut.flush();
                        } catch (IOException e) {
                            log.trace("Telnet 保活发送结束: {}", e.getMessage());
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return Optional.of(new TelnetSessionHolder(telnet, pipeOut, closed));
    }

    /**
     * Telnet 自动登录：先发回车唤醒设备，读取输出并转发到 Web 终端，遇到用户名/密码提示时自动发送设备管理中的账号密码。
     * 兼容锐捷/华为等：User Access Verification、Username:、Password: 等。
     */
    private static void telnetDoLogin(InputStream in, OutputStream telnetOut, OutputStream toWeb,
                                     String username, String password) throws IOException, InterruptedException {
        telnetOut.write("\r\n".getBytes(StandardCharsets.UTF_8));
        telnetOut.flush();
        Thread.sleep(250);

        long deadline = System.currentTimeMillis() + 15000;
        long lastRead = System.currentTimeMillis();
        StringBuilder buf = new StringBuilder();
        byte[] b = new byte[512];
        boolean sentUser = false;
        boolean sentPass = false;

        while (System.currentTimeMillis() < deadline && (!sentUser || !sentPass || System.currentTimeMillis() - lastRead < 1200)) {
            if (in.available() > 0) {
                int n = in.read(b);
                if (n > 0) {
                    toWeb.write(b, 0, n);
                    toWeb.flush();
                    buf.append(new String(b, 0, n, StandardCharsets.UTF_8));
                    lastRead = System.currentTimeMillis();
                    if (buf.length() > 4096) buf.delete(0, buf.length() - 2048);
                }
                String lower = buf.toString().toLowerCase();
                // 用户名提示：英文 + 常见中文（锐捷/华为等）
                boolean userPrompt = !sentUser && (lower.contains("username") || lower.contains("login") || lower.contains("user access")
                        || lower.contains("user name") || lower.contains("login:") || lower.contains("用户名") || lower.contains("请输入用户名") || lower.contains("login name"));
                if (userPrompt) {
                    telnetOut.write((username + "\r\n").getBytes(StandardCharsets.UTF_8));
                    telnetOut.flush();
                    sentUser = true;
                    Thread.sleep(150);
                }
                // 密码提示：英文 + 常见中文
                boolean passPrompt = sentUser && !sentPass && (lower.contains("password") || lower.contains("passwd") || lower.contains("密码") || lower.contains("请输入密码"));
                if (passPrompt) {
                    telnetOut.write((password + "\r\n").getBytes(StandardCharsets.UTF_8));
                    telnetOut.flush();
                    sentPass = true;
                }
            } else {
                Thread.sleep(40);
            }
        }
    }

    public void disconnect(ShellSessionHolder holder) {
        if (holder != null) {
            holder.disconnect();
        }
    }

    /** 统一会话抽象：SSH 与 Telnet 均输出到同一 Web 终端 */
    public interface ShellSessionHolder {
        PipedOutputStream getPipeOut();
        void disconnect();
    }

    @Getter
    public static class SshSessionHolder implements ShellSessionHolder {
        private final Session session;
        private final PipedOutputStream pipeOut;

        public SshSessionHolder(Session session, PipedOutputStream pipeOut) {
            this.session = session;
            this.pipeOut = pipeOut;
        }

        @Override
        public void disconnect() {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Getter
    public static class TelnetSessionHolder implements ShellSessionHolder {
        private final TelnetClient telnet;
        private final PipedOutputStream pipeOut;
        private final AtomicBoolean closed;

        public TelnetSessionHolder(TelnetClient telnet, PipedOutputStream pipeOut, AtomicBoolean closed) {
            this.telnet = telnet;
            this.pipeOut = pipeOut;
            this.closed = closed != null ? closed : new AtomicBoolean(false);
        }

        @Override
        public void disconnect() {
            if (closed.compareAndSet(false, true) && telnet != null && telnet.isConnected()) {
                try {
                    telnet.disconnect();
                } catch (IOException e) {
                    log.debug("Telnet 断开: {}", e.getMessage());
                }
            }
        }
    }
}
