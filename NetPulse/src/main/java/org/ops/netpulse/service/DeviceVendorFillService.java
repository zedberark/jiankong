package org.ops.netpulse.service;

import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.util.OsReleaseParser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

/**
 * 定时为「在线、厂商为空、已配置 SSH」的 Linux 服务器通过 SSH 执行 grep -E "^(NAME|ID|ID_LIKE|VERSION)" /etc/os-release 自动写入厂商。
 */
@Service
@Slf4j
public class DeviceVendorFillService {

    private static final int MAX_PER_RUN = 3;

    private final DeviceRepository deviceRepository;
    private final BatchCommandService batchCommandService;

    public DeviceVendorFillService(DeviceRepository deviceRepository, BatchCommandService batchCommandService) {
        this.deviceRepository = deviceRepository;
        this.batchCommandService = batchCommandService;
    }

    /** 每 10 分钟执行一次：对符合条件的 Linux 在线设备（厂商为空、已配 SSH）通过 SSH 获取 os-release 并写入厂商 */
    @Scheduled(fixedDelayString = "${device.vendor-fill-interval:600000}")
    @Transactional
    public void fillVendorForOnlineLinuxServers() {
        List<Device> all = deviceRepository.findByDeletedFalse();
        Stream<Device> candidates = all.stream()
                .filter(d -> d.getType() != null && d.getType() == Device.DeviceType.server)
                .filter(d -> d.getStatus() != null && d.getStatus() != Device.DeviceStatus.offline)
                .filter(d -> d.getVendor() == null || d.getVendor().isBlank())
                .filter(d -> d.getSshUser() != null && !d.getSshUser().isBlank()
                        && d.getSshPassword() != null && !d.getSshPassword().isBlank())
                .limit(MAX_PER_RUN);
        candidates.forEach(this::tryFillVendor);
    }

    private void tryFillVendor(Device d) {
        try {
            String osRelease = batchCommandService.runCommand(d, "grep -E \"^(NAME|ID|ID_LIKE|VERSION)\" /etc/os-release 2>/dev/null || true", false);
            if (osRelease == null || osRelease.isBlank()) return;
            String vendor = OsReleaseParser.parseVendor(osRelease);
            if (vendor == null || vendor.isBlank()) return;
            if (d.getId() != null) {
                deviceRepository.updateVendorById(d.getId(), vendor);
            }
            log.info("自动写入厂商 id={} ip={} vendor={}", d.getId(), d.getIp(), vendor);
        } catch (Exception e) {
            log.debug("自动获取厂商失败 id={}: {}", d.getId(), e.getMessage());
        }
    }
}
