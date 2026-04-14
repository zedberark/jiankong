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
import java.util.List;
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
    /**
     * 兼容老旧网络设备（如部分 IOSv 镜像）只支持的 SHA1 KEX。
     * 放在末尾作为回退，优先仍使用更安全的新算法。
     */
    private static final String SSH_KEX_COMPAT_LIST = String.join(",",
            "curve25519-sha256",
            "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp256",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521",
            "diffie-hellman-group-exchange-sha256",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group18-sha512",
            "diffie-hellman-group14-sha256",
            "diffie-hellman-group14-sha1",
            "diffie-hellman-group-exchange-sha1",
            "diffie-hellman-group1-sha1"
    );
    /** 兼容老设备仅支持的主机密钥算法。 */
    private static final String SSH_SERVER_HOST_KEY_COMPAT_LIST = String.join(",",
            "ssh-ed25519",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "rsa-sha2-512",
            "rsa-sha2-256",
            "ssh-rsa",
            "ssh-dss"
    );
    /** 兼容老设备常见加密算法（含 aes-cbc / 3des-cbc 回退）。 */
    private static final String SSH_CIPHER_COMPAT_LIST = String.join(",",
            "aes128-ctr",
            "aes192-ctr",
            "aes256-ctr",
            "aes128-cbc",
            "aes192-cbc",
            "aes256-cbc",
            "3des-cbc"
    );
    /** 兼容老设备 MAC 算法。 */
    private static final String SSH_MAC_COMPAT_LIST = String.join(",",
            "hmac-sha2-256",
            "hmac-sha2-512",
            "hmac-sha1",
            "hmac-md5"
    );
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
            @Value("${webssh.ssh-session-timeout-ms:0}") int sshSessionSocketTimeoutMs) {
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

    /** 依次尝试多种 PTY/终端类型：部分设备对 xterm 报 SSH_MSG_DISCONNECT(2) 协议错误，改用 vt100 或无 PTY 可连上。 */
    private static final List<PtyProfile> SSH_SHELL_PROFILES = List.of(
            new PtyProfile(true, "xterm", 120, 40),
            new PtyProfile(true, "vt100", 80, 24),
            new PtyProfile(true, "ansi", 80, 24),
            new PtyProfile(false, null, 0, 0)
    );

    private record PtyProfile(boolean usePty, String term, int cols, int rows) {}

    private Optional<ShellSessionHolder> createSshSession(Device device, int port, OutputStream out) throws JSchException, IOException {
        Session session = newConnectedSshSession(device, port);
        JSchException lastError = null;
        for (PtyProfile profile : SSH_SHELL_PROFILES) {
            if (!session.isConnected()) {
                session = newConnectedSshSession(device, port);
            }
            PipedInputStream pipeIn = new PipedInputStream(64 * 1024);
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
            ChannelShell channel = null;
            try {
                channel = (ChannelShell) session.openChannel("shell");
                if (profile.usePty) {
                    channel.setPty(true);
                    channel.setPtyType(profile.term != null ? profile.term : "vt100");
                    int c = profile.cols > 0 ? profile.cols : 80;
                    int r = profile.rows > 0 ? profile.rows : 24;
                    channel.setPtySize(c, r, c * 8, r * 16);
                } else {
                    channel.setPty(false);
                }
                channel.setInputStream(pipeIn);
                channel.setOutputStream(out);
                channel.connect(sshChannelConnectTimeoutMs);
                log.info("Web SSH shell 已建立 ip={} profile={} pty={}", device.getIp(), profile.term != null ? profile.term : "none", profile.usePty);
                return Optional.of(new SshSessionHolder(session, pipeOut));
            } catch (JSchException e) {
                lastError = e;
                log.debug("Web SSH shell 尝试失败 ip={} pty={} term={}: {}", device.getIp(), profile.usePty, profile.term, e.getMessage());
                try {
                    if (channel != null && channel.isConnected()) {
                        channel.disconnect();
                    }
                } catch (Exception ignored) {
                }
                try {
                    pipeOut.close();
                } catch (IOException ignored) {
                }
                try {
                    pipeIn.close();
                } catch (IOException ignored) {
                }
            }
        }
        try {
            session.disconnect();
        } catch (Exception ignored) {
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new JSchException("SSH shell 通道打开失败（已尝试多种终端配置）");
    }

    private Session newConnectedSshSession(Device device, int port) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(device.getSshUser(), device.getIp(), port);
        session.setPassword(device.getSshPassword());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        // 兼容老旧设备仅支持的 SHA1 KEX，避免 Algorithm negotiation fail。
        config.put("kex", SSH_KEX_COMPAT_LIST);
        config.put("server_host_key", SSH_SERVER_HOST_KEY_COMPAT_LIST);
        config.put("cipher.s2c", SSH_CIPHER_COMPAT_LIST);
        config.put("cipher.c2s", SSH_CIPHER_COMPAT_LIST);
        config.put("mac.s2c", SSH_MAC_COMPAT_LIST);
        config.put("mac.c2s", SSH_MAC_COMPAT_LIST);
        config.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        config.put("ServerAliveInterval", "45");
        config.put("ServerAliveCountMax", "3");
        session.setConfig(config);
        session.connect(sshConnectTimeoutMs);
        // Web 交互终端默认不设置 socket 读超时（0=无限制），避免空闲时被客户端误判断开。
        session.setTimeout(Math.max(0, sshSessionSocketTimeoutMs));
        return session;
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
