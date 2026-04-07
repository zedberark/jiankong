package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.dto.InspectionItemRow;
import org.ops.netpulse.dto.InspectionReportDetailDto;
import org.ops.netpulse.dto.InspectionReportSummary;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.entity.InspectionItem;
import org.ops.netpulse.entity.InspectionReport;
import org.ops.netpulse.repository.DeviceRepository;
import org.ops.netpulse.repository.InspectionReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 系统巡检：对设备批量做可达性探测（与定时任务逻辑一致，但不写 Influx、不触发告警），生成可查询的报告。
 */
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final DeviceRepository deviceRepository;
    private final MonitorService monitorService;
    private final InspectionReportRepository inspectionReportRepository;
    private final AiChatService aiChatService;

    /** 并发探测线程数；≤1 时串行，与 monitor.ping-timeout 共同影响单次巡检总耗时 */
    @Value("${inspection.probe-parallelism:16}")
    private int probeParallelism;

    /** 手动巡检：来源 MANUAL */
    @Transactional
    public InspectionReport runInspection(String groupName) {
        return runInspection(groupName, "MANUAL", null);
    }

    /**
     * @param source       MANUAL / HOURLY / DAILY_00 / DAILY_18 / WEEKLY_MON / WEEKLY_SUN
     * @param scheduleLabel 定时任务展示名，手动可为 null
     */
    @Transactional
    public InspectionReport runInspection(String groupName, String source, String scheduleLabel) {
        List<Device> devices = listDevicesForGroup(groupName);
        LocalDateTime createdAt = LocalDateTime.now();
        long t0 = System.currentTimeMillis();
        String src = (source != null && !source.isBlank()) ? source.trim() : "MANUAL";

        InspectionReport report = InspectionReport.builder()
                .createdAt(createdAt)
                .groupName(trimToNull(groupName))
                .totalCount(devices.size())
                .okCount(0)
                .warnCount(0)
                .offlineCount(0)
                .source(src)
                .scheduleLabel(trimToNull(scheduleLabel))
                .build();

        int ok = 0;
        int warn = 0;
        int offline = 0;
        List<InspectionItem> items = new ArrayList<>();
        List<ProbeResult> probes = runProbes(devices);
        for (ProbeResult pr : probes) {
            switch (pr.status()) {
                case "normal" -> ok++;
                case "warning" -> warn++;
                default -> offline++;
            }
            InspectionItem row = InspectionItem.builder()
                    .report(report)
                    .deviceId(pr.deviceId())
                    .deviceName(pr.deviceName())
                    .ip(pr.ip())
                    .deviceType(pr.deviceTypeStr())
                    .rttMs(pr.rttMs())
                    .status(pr.status())
                    .build();
            items.add(row);
        }
        report.setItems(items);
        report.setOkCount(ok);
        report.setWarnCount(warn);
        report.setOfflineCount(offline);
        report.setFinishedAt(LocalDateTime.now());
        report.setDurationMs(System.currentTimeMillis() - t0);
        return inspectionReportRepository.save(report);
    }

    public Page<InspectionReportSummary> listReports(String source, Pageable pageable) {
        if (source == null || source.isBlank()) {
            return inspectionReportRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return inspectionReportRepository.findBySourceOrderByCreatedAtDesc(source.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Optional<InspectionReportDetailDto> getReportDetail(Long id) {
        return inspectionReportRepository.findByIdWithItems(id).map(this::toDetailDto);
    }

    /**
     * 调用千问/DeepSeek 根据报告明细生成 AI 结论并落库，返回最新详情。
     */
    @Transactional
    public Optional<InspectionReportDetailDto> generateAiSummary(Long reportId, String username) {
        Optional<InspectionReport> opt = inspectionReportRepository.findByIdWithItems(reportId);
        if (opt.isEmpty()) return Optional.empty();
        InspectionReport r = opt.get();
        InspectionReportDetailDto dto = toDetailDto(r);
        String text = aiChatService.summarizeInspectionReport(username, dto);
        r.setAiSummary(text);
        inspectionReportRepository.save(r);
        return getReportDetail(reportId);
    }

    /** 供控制器在写入 AI 摘要后重新加载带 items 的报告实体 */
    @Transactional(readOnly = true)
    public Optional<InspectionReport> findReportWithItems(Long id) {
        return inspectionReportRepository.findByIdWithItems(id);
    }

    @Transactional
    public boolean deleteReport(Long id) {
        if (!inspectionReportRepository.existsById(id)) {
            return false;
        }
        inspectionReportRepository.deleteById(id);
        return true;
    }

    private InspectionReportDetailDto toDetailDto(InspectionReport r) {
        List<InspectionItemRow> rows = r.getItems().stream()
                .map(it -> InspectionItemRow.builder()
                        .id(it.getId())
                        .deviceId(it.getDeviceId())
                        .deviceName(it.getDeviceName())
                        .ip(it.getIp())
                        .deviceType(it.getDeviceType())
                        .rttMs(it.getRttMs())
                        .status(it.getStatus())
                        .build())
                .collect(Collectors.toList());
        return InspectionReportDetailDto.builder()
                .id(r.getId())
                .createdAt(r.getCreatedAt())
                .finishedAt(r.getFinishedAt())
                .groupName(r.getGroupName())
                .totalCount(r.getTotalCount())
                .okCount(r.getOkCount())
                .warnCount(r.getWarnCount())
                .offlineCount(r.getOfflineCount())
                .durationMs(r.getDurationMs())
                .source(r.getSource())
                .scheduleLabel(r.getScheduleLabel())
                .aiSummary(r.getAiSummary())
                .items(rows)
                .build();
    }

    private List<Device> listDevicesForGroup(String groupName) {
        String g = trimToNull(groupName);
        if (g == null) {
            return deviceRepository.findByDeletedFalse();
        }
        return deviceRepository.findByDeletedFalseAndGroupName(g);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 与 MonitorService.updateDeviceStatusFromReachable 中 RTT→状态 一致 */
    private static String statusFromRtt(long rtt) {
        if (rtt < 0) return "offline";
        if (rtt > 800) return "warning";
        return "normal";
    }

    private List<ProbeResult> runProbes(List<Device> devices) {
        if (devices.isEmpty()) {
            return List.of();
        }
        if (probeParallelism <= 1) {
            List<ProbeResult> out = new ArrayList<>(devices.size());
            for (Device d : devices) {
                out.add(probeOne(d));
            }
            return out;
        }
        int threads = Math.min(probeParallelism, devices.size());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<ProbeResult>> futures = new ArrayList<>(devices.size());
            for (Device d : devices) {
                futures.add(CompletableFuture.supplyAsync(() -> probeOne(d), executor));
            }
            List<ProbeResult> out = new ArrayList<>(devices.size());
            for (CompletableFuture<ProbeResult> f : futures) {
                out.add(f.join());
            }
            return out;
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private ProbeResult probeOne(Device d) {
        long rtt = monitorService.probeReachabilityOnly(
                d.getIp(), d.getType(), d.getSnmpPort(), d.getSshPort());
        String status = statusFromRtt(rtt);
        String typeStr = d.getType() != null ? d.getType().name() : null;
        return new ProbeResult(
                d.getId(),
                d.getName(),
                d.getIp(),
                typeStr,
                rtt >= 0 ? rtt : null,
                status);
    }

    private record ProbeResult(
            long deviceId,
            String deviceName,
            String ip,
            String deviceTypeStr,
            Long rttMs,
            String status) {
    }
}
