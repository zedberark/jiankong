package org.ops.netpulse.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.ops.netpulse.entity.Device.DeviceStatus;

import java.net.InetAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 设备监控：定时 Ping（及网络设备 TCP 161/22 探测），更新设备状态并写 InfluxDB device_status；
 * 状态变化时触发告警规则与审计。RTT 与状态供仪表盘时间线等使用。
 */
@Service
@Slf4j
public class MonitorService {

    private final DeviceRepository deviceRepository;
    private final InfluxDBClient influxDBClient;
    private final AlertService alertService;

    public MonitorService(DeviceRepository deviceRepository,
                          @Autowired(required = false) InfluxDBClient influxDBClient,
                          @Autowired(required = false) AlertService alertService) {
        this.deviceRepository = deviceRepository;
        this.influxDBClient = influxDBClient;
        this.alertService = alertService;
    }

    @Value("${influxdb.bucket:metrics}")
    private String bucket;

    @Value("${influxdb.org:netpulse}")
    private String influxOrg;

    @Value("${monitor.ping-timeout:3000}")
    private int pingTimeoutMs;

    /** 定时采集：对所有未删除设备做可达性检测，更新 status/lastPollTime 并写 InfluxDB；状态变化时触发告警。默认 60 秒，提高在线/离线同步率。 */
    @Scheduled(fixedDelayString = "${monitor.collect-interval:60}000")
    @Transactional
    public void collectMetrics() {
        List<Device> devices = deviceRepository.findByDeletedFalse();
        if (devices.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        WriteApiBlocking writeApi = influxDBClient != null ? influxDBClient.getWriteApiBlocking() : null;

        for (Device d : devices) {
            try {
                DeviceStatus previousStatus = d.getStatus();
                long rtt = resolveReachable(d);
                int status = rtt >= 0 ? 1 : 0;
                DeviceStatus newStatus = rtt < 0 ? DeviceStatus.offline : (rtt > 800 ? DeviceStatus.warning : DeviceStatus.normal);
                deviceRepository.updateStatusAndLastPollTime(d.getId(), newStatus, now);
                if (alertService != null && previousStatus != newStatus) {
                    alertService.onDeviceStatusChange(d, previousStatus, newStatus);
                }
                if (writeApi != null) {
                    Point point = Point.measurement("device_status")
                            .addTag("device_id", String.valueOf(d.getId()))
                            .addTag("device_name", d.getName())
                            .addTag("type", d.getType() != null ? d.getType().toValue() : "other")
                            .addField("rtt_ms", rtt >= 0 ? rtt : 0)
                            .addField("status", status)
                            .time(System.currentTimeMillis(), WritePrecision.MS);
                    writeApi.writePoint(bucket, influxOrg, point);
                }
            } catch (Exception e) {
                log.warn("Collect failed for device {}: {}", d.getName(), e.getMessage());
                DeviceStatus previousStatus = d.getStatus();
                deviceRepository.updateStatusAndLastPollTime(d.getId(), DeviceStatus.offline, now);
                if (alertService != null && previousStatus != DeviceStatus.offline) {
                    alertService.onDeviceStatusChange(d, previousStatus, DeviceStatus.offline);
                }
            }
        }
    }

    /**
     * 对单台设备做可达性检测并更新 DB 中的 status、lastPollTime，状态变化时触发告警。
     * 供设备列表「Ping」按钮调用，使列表立即显示在线/离线，无需等定时任务。
     * @return RTT 毫秒，不可达返回 -1
     */
    @Transactional
    public long updateDeviceStatusFromReachable(Device d) {
        DeviceStatus previousStatus = d.getStatus();
        long rtt = resolveReachable(d);
        LocalDateTime now = LocalDateTime.now();
        DeviceStatus newStatus = rtt < 0 ? DeviceStatus.offline : (rtt > 800 ? DeviceStatus.warning : DeviceStatus.normal);
        deviceRepository.updateStatusAndLastPollTime(d.getId(), newStatus, now); // 仅更新状态与轮询时间，不覆盖 name 等字段
        if (alertService != null && previousStatus != newStatus) {
            alertService.onDeviceStatusChange(d, previousStatus, newStatus);
        }
        if (influxDBClient != null) {
            try {
                WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
                int status = rtt >= 0 ? 1 : 0;
                Point point = Point.measurement("device_status")
                        .addTag("device_id", String.valueOf(d.getId()))
                        .addTag("device_name", d.getName())
                        .addTag("type", d.getType() != null ? d.getType().toValue() : "other")
                        .addField("rtt_ms", rtt >= 0 ? rtt : 0)
                        .addField("status", status)
                        .time(System.currentTimeMillis(), WritePrecision.MS);
                writeApi.writePoint(bucket, influxOrg, point);
            } catch (Exception e) {
                log.trace("InfluxDB write after manual ping: {}", e.getMessage());
            }
        }
        return rtt;
    }

    /**
     * 仅探测可达性并返回 RTT（毫秒），不写库、不触发告警。供「系统巡检」批量生成报告使用。
     */
    public long probeReachabilityOnly(Device d) {
        return probeReachabilityOnly(
                d.getIp(),
                d.getType(),
                d.getSnmpPort(),
                d.getSshPort());
    }

    /**
     * 与 {@link #probeReachabilityOnly(Device)} 逻辑一致，仅使用字段，便于巡检在多线程中调用（不持有 Device 实体）。
     */
    public long probeReachabilityOnly(String ip, Device.DeviceType type, Integer snmpPort, Integer sshPort) {
        if (ip == null || ip.isBlank()) return -1;
        long rtt = ping(ip);
        if (rtt >= 0) return rtt;
        // 仅 server 不做 TCP 回退；路由器/交换机/防火墙/other 均尝试 TCP，避免类型填错或仅开 Telnet 时误判离线
        if (type == Device.DeviceType.server) return -1;
        int snmp = snmpPort != null ? snmpPort : 161;
        int ssh = sshPort != null ? sshPort : 22;
        int tcpTimeout = Math.min(pingTimeoutMs, 3000);
        long tcpRtt = tcpReachable(ip, snmp, tcpTimeout);
        if (tcpRtt >= 0) return tcpRtt;
        tcpRtt = tcpReachable(ip, ssh, tcpTimeout);
        if (tcpRtt >= 0) return tcpRtt;
        if (ssh != 23) {
            tcpRtt = tcpReachable(ip, 23, tcpTimeout);
            if (tcpRtt >= 0) return tcpRtt;
        }
        return -1;
    }

    /**
     * 判断设备是否可达并返回 RTT（毫秒），失败返回 -1。
     * 先 ICMP；失败则对非 server 设备做 TCP 探测：SNMP(161)、设备配置的 SSH/Telnet 端口、以及 23(Telnet)，
     * 避免仅靠 ICMP 时“能 ping 通却显示离线”（Windows 上 isReachable 不可靠或设备禁 ping）。
     */
    private long resolveReachable(Device d) {
        return probeReachabilityOnly(d.getIp(), d.getType(), d.getSnmpPort(), d.getSshPort());
    }

    /** ICMP Ping 检测，返回 RTT 毫秒，失败返回 -1（Windows 上可能不可靠） */
    public long ping(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            long start = System.nanoTime();
            boolean reachable = address.isReachable(pingTimeoutMs);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return reachable ? Math.max(elapsed, 0) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** TCP 端口可达性检测，返回连接耗时毫秒，失败返回 -1 */
    private long tcpReachable(String host, int port, int timeoutMs) {
        long start = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (Exception e) {
            return -1;
        }
    }
}
