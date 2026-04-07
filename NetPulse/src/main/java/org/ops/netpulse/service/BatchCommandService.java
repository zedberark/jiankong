package org.ops.netpulse.service;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.telnet.TelnetClient;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.Device.DeviceStatus;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 批量命令执行：按设备端口选择协议——端口 23 用 Telnet，否则用 SSH。
 * 用于批量命令页、设备指标采集、告警自动修复等；设备 IP 或用户名密码未配置时直接返回失败。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BatchCommandService {

    private static final int TELNET_PORT = 23;
    private static final int TELNET_READ_TIMEOUT_MS = 8000;
    private static final int TELNET_IDLE_MS = 2500;

    private final DeviceRepository deviceRepository;

    /** 单条命令执行结果：设备 ID、名称、是否成功、标准输出与错误输出 */
    public static class CommandResult {
        public Long deviceId;
        public String deviceName;
        public boolean success;
        public String output;
        public String error;
    }

    /** 执行单条命令并返回标准输出（失败返回 null），供采集 CPU/内存等使用。离线设备不执行（SSH/Telnet 均跳过）。 */
    public String runCommand(Device d, String command) {
        return runCommand(d, command, false);
    }

    /** 执行单条命令。skipOfflineCheck 为 true 时不因离线跳过（用于添加/编辑后尝试通过 SSH 获取主机名）。 */
    public String runCommand(Device d, String command, boolean skipOfflineCheck) {
        if (d == null || d.getSshUser() == null || d.getSshPassword() == null) return null;
        if (d.getIp() == null || d.getIp().isBlank()) return null;
        if (!skipOfflineCheck && d.getStatus() == DeviceStatus.offline) return null;
        CommandResult r = execOne(d, command);
        return r.success && r.output != null ? r.output.trim() : null;
    }

    public List<CommandResult> execute(List<Long> deviceIds, String command) {
        List<CommandResult> results = new ArrayList<>();
        for (Long id : deviceIds) {
            Device d = deviceRepository.findById(id).filter(x -> !Boolean.TRUE.equals(x.getDeleted())).orElse(null);
            if (d == null) {
                CommandResult r = new CommandResult();
                r.deviceId = id;
                r.deviceName = "设备#" + id;
                r.success = false;
                r.error = "设备不存在或已删除";
                results.add(r);
                continue;
            }
            if (d.getSshUser() == null || d.getSshPassword() == null) {
                CommandResult r = new CommandResult();
                r.deviceId = id;
                r.deviceName = d.getName() != null ? d.getName() : (d.getIp() != null ? d.getIp() : "设备#" + id);
                r.success = false;
                r.error = "未配置 SSH 用户名或密码";
                results.add(r);
                continue;
            }
            if (d.getStatus() == DeviceStatus.offline) {
                CommandResult r = new CommandResult();
                r.deviceId = id;
                r.deviceName = d.getName() != null ? d.getName() : (d.getIp() != null ? d.getIp() : "设备#" + id);
                r.success = false;
                r.error = "设备离线，跳过执行";
                results.add(r);
                continue;
            }
            results.add(execOne(d, command));
        }
        return results;
    }

    private CommandResult execOne(Device d, String command) {
        int port = d.getSshPort() != null ? d.getSshPort() : 22;
        if (port == TELNET_PORT) {
            return execOneTelnet(d, command);
        }
        return execOneSsh(d, command);
    }

    /** 端口 23：用 Telnet 连接并执行命令，发送 command+回车后读取一段时间内的输出 */
    private CommandResult execOneTelnet(Device d, String command) {
        CommandResult r = new CommandResult();
        r.deviceId = d.getId();
        r.deviceName = d.getName() != null ? d.getName() : (d.getIp() != null ? d.getIp() : "设备#" + d.getId());
        if (d.getIp() == null || d.getIp().isBlank()) {
            r.success = false;
            r.error = "设备未配置管理 IP";
            return r;
        }
        TelnetClient telnet = new TelnetClient();
        telnet.setConnectTimeout(10000);
        try {
            telnet.connect(d.getIp(), d.getSshPort() != null ? d.getSshPort() : TELNET_PORT);
            InputStream in = telnet.getInputStream();
            java.io.OutputStream outStream = telnet.getOutputStream();
            String first = readTelnetForMs(in, 2000);
            String lower = first != null ? first.toLowerCase() : "";
            if (lower.contains("login") || lower.contains("username") || lower.contains("user name")) {
                outStream.write((d.getSshUser() + "\r\n").getBytes(StandardCharsets.UTF_8));
                outStream.flush();
                String second = readTelnetForMs(in, 2000);
                String s2 = second != null ? second.toLowerCase() : "";
                if (s2.contains("password") || s2.contains("passwd")) {
                    outStream.write((d.getSshPassword() + "\r\n").getBytes(StandardCharsets.UTF_8));
                    outStream.flush();
                    Thread.sleep(300);
                }
            }
            byte[] cmdBytes = (command + "\r\n").getBytes(StandardCharsets.UTF_8);
            outStream.write(cmdBytes);
            outStream.flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + TELNET_READ_TIMEOUT_MS;
            long lastRead = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline && (System.currentTimeMillis() - lastRead) < TELNET_IDLE_MS) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        lastRead = System.currentTimeMillis();
                    }
                } else {
                    Thread.sleep(50);
                }
            }
            r.success = true;
            r.output = out.toString(StandardCharsets.UTF_8);
            r.error = "";
        } catch (Exception e) {
            r.success = false;
            String msg = e.getMessage();
            r.error = msg != null && !msg.isBlank() ? msg : "Telnet 连接或读取超时";
        } finally {
            try {
                if (telnet.isConnected()) telnet.disconnect();
            } catch (IOException ignored) {}
        }
        return r;
    }

    private static String readTelnetForMs(InputStream in, int timeoutMs) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastRead = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline && (System.currentTimeMillis() - lastRead) < 800) {
            if (in.available() > 0) {
                int n = in.read(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                    lastRead = System.currentTimeMillis();
                }
            } else {
                Thread.sleep(50);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private CommandResult execOneSsh(Device d, String command) {
        CommandResult r = new CommandResult();
        r.deviceId = d.getId();
        r.deviceName = d.getName() != null ? d.getName() : (d.getIp() != null ? d.getIp() : "设备#" + d.getId());
        if (d.getIp() == null || d.getIp().isBlank()) {
            r.success = false;
            r.error = "设备未配置管理 IP";
            return r;
        }
        JSch jsch = new JSch();
        Session session = null;
        try {
            session = jsch.getSession(d.getSshUser(), d.getIp(), d.getSshPort() != null ? d.getSshPort() : 22);
            session.setPassword(d.getSshPassword());
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(10000);
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.setErrStream(err);
            channel.connect(15000);
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }
            r.output = new String(out.toByteArray(), StandardCharsets.UTF_8);
            r.error = new String(err.toByteArray(), StandardCharsets.UTF_8);
            r.success = (r.output != null && !r.output.isBlank()) || channel.getExitStatus() == 0;
        } catch (Exception e) {
            r.success = false;
            String msg = e.getMessage();
            r.error = msg != null && !msg.isBlank() ? msg : "连接超时或执行异常";
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
        return r;
    }
}
