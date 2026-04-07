package org.ops.netpulse.service;

import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本机（运行监控运维系统的 Windows/Linux 主机）系统监控：CPU、内存、磁盘、网络流量。
 */
@Service
@Slf4j
public class LocalHostStatsService {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;
    private static final long KIB = 1024L;

    private final AtomicReference<long[]> lastNetwork = new AtomicReference<>(null);
    private final AtomicLong lastNetworkTime = new AtomicLong(0);

    /**
     * 返回本机 CPU 使用率、内存、磁盘、网络流量（含上传/下载速率）。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> out = new HashMap<>();
        try {
            SystemInfo si = new SystemInfo();
            HardwareAbstractionLayer hal = si.getHardware();
            OperatingSystem os = si.getOperatingSystem();
            FileSystem fs = os.getFileSystem();

            // CPU 使用率 (0–100)，blockingMs 内采样取平均
            CentralProcessor cpu = hal.getProcessor();
            double cpuLoad = cpu.getSystemCpuLoad(400);
            if (cpuLoad < 0) cpuLoad = 0;
            if (cpuLoad > 1) cpuLoad = 1;
            out.put("cpuUsagePercent", Math.round(cpuLoad * 1000) / 10.0);

            // 内存：总/已用 GB，使用率
            GlobalMemory mem = hal.getMemory();
            long totalBytes = mem.getTotal();
            long availableBytes = mem.getAvailable();
            long usedBytes = totalBytes - availableBytes;
            double totalGb = totalBytes / (double) GIB;
            double usedGb = usedBytes / (double) GIB;
            double memPct = totalBytes > 0 ? (usedBytes * 100.0 / totalBytes) : 0;
            out.put("memoryTotalGb", Math.round(totalGb * 10) / 10.0);
            out.put("memoryUsedGb", Math.round(usedGb * 10) / 10.0);
            out.put("memoryUsagePercent", Math.round(memPct * 10) / 10.0);

            // 磁盘：所有挂载点合计 总/已用 GB，使用率
            long diskTotal = 0;
            long diskUsed = 0;
            for (OSFileStore store : fs.getFileStores()) {
                try {
                    long total = store.getTotalSpace();
                    long free = store.getUsableSpace();
                    if (total > 0 && total != Long.MAX_VALUE) {
                        diskTotal += total;
                        diskUsed += (total - free);
                    }
                } catch (Exception e) {
                    log.trace("FileStore {}: {}", store.getName(), e.getMessage());
                }
            }
            double diskTotalGb = diskTotal / (double) GIB;
            double diskUsedGb = diskUsed / (double) GIB;
            double diskPct = diskTotal > 0 ? (diskUsed * 100.0 / diskTotal) : 0;
            out.put("diskTotalGb", Math.round(diskTotalGb * 10) / 10.0);
            out.put("diskUsedGb", Math.round(diskUsedGb * 10) / 10.0);
            out.put("diskUsagePercent", Math.round(diskPct * 10) / 10.0);

            // 网络：当前速率（基于与上次采样的差值）
            long now = System.currentTimeMillis();
            long totalSent = 0;
            long totalRecv = 0;
            for (NetworkIF net : hal.getNetworkIFs()) {
                net.updateAttributes();
                totalSent += net.getBytesSent();
                totalRecv += net.getBytesRecv();
            }
            long[] prev = lastNetwork.getAndSet(new long[] { totalSent, totalRecv });
            long prevTime = lastNetworkTime.getAndSet(now);
            double uploadKbps = 0;
            double downloadKbps = 0;
            if (prev != null && (now - prevTime) > 200) {
                double sec = (now - prevTime) / 1000.0;
                if (sec > 0) {
                    uploadKbps = (totalSent - prev[0]) / sec / KIB;
                    downloadKbps = (totalRecv - prev[1]) / sec / KIB;
                    if (uploadKbps < 0) uploadKbps = 0;
                    if (downloadKbps < 0) downloadKbps = 0;
                }
            }
            out.put("networkUploadKbps", Math.round(uploadKbps * 10) / 10.0);
            out.put("networkDownloadKbps", Math.round(downloadKbps * 10) / 10.0);
            out.put("timestamp", now);

        } catch (Exception e) {
            log.warn("本机系统监控采集异常: {}", e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }
}
