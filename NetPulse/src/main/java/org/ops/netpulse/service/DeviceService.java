package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 设备业务：设备 CRUD、按分组查询、逻辑删除、Ping 检测、健康汇总、批量导入。
 * Ping 依赖 MonitorService；设备状态由 MonitorService 定时采集更新。
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final MonitorService monitorService;

    /** 查询所有未删除设备 */
    public List<Device> findAll() {
        return deviceRepository.findByDeletedFalse();
    }

    /** 按分组查询，groupName 为空则查全部 */
    public List<Device> findAll(String groupName) {
        if (groupName == null || groupName.isBlank()) return deviceRepository.findByDeletedFalse();
        return deviceRepository.findByDeletedFalseAndGroupName(groupName.trim());
    }

    /** 所有不重复的分组名称，供前端筛选与表单下拉 */
    public List<String> getGroupNames() {
        return deviceRepository.findDistinctGroupNames();
    }

    /** 按 ID 查询，已逻辑删除的不返回 */
    public Optional<Device> findById(Long id) {
        return deviceRepository.findById(id).filter(d -> !Boolean.TRUE.equals(d.getDeleted()));
    }

    /** 新增或更新设备 */
    @Transactional
    public Device save(Device device) {
        return deviceRepository.save(device);
    }

    /** 逻辑删除 */
    @Transactional
    public void deleteById(Long id) {
        deviceRepository.findById(id).ifPresent(d -> {
            d.setDeleted(true);
            deviceRepository.save(d);
        });
    }

    /** 即时 Ping 检测：返回 RTT 毫秒，失败或 IP 为空返回 -1（不写库） */
    public long pingDevice(Long deviceId) {
        return findById(deviceId)
                .map(d -> {
                    String ip = d.getIp();
                    if (ip == null || ip.isBlank()) return -1L;
                    return monitorService.ping(ip);
                })
                .orElse(-1L);
    }

    /** Ping 并更新设备状态与最后轮询时间，使设备列表立即显示在线/离线 */
    @Transactional
    public long pingAndUpdateStatus(Long deviceId) {
        return findById(deviceId)
                .map(monitorService::updateDeviceStatusFromReachable)
                .orElse(-1L);
    }

    /** 健康状态汇总：按 status 统计数量（不传 group 表示全部） */
    public java.util.Map<Device.DeviceStatus, Long> healthSummary() {
        return healthSummary(null);
    }

    /** 健康状态汇总：仅统计指定分组内的设备；groupName 为空时统计全部 */
    public java.util.Map<Device.DeviceStatus, Long> healthSummary(String groupName) {
        List<Device> list = findAll(groupName);
        return list.stream()
                .filter(d -> d.getStatus() != null)
                .collect(java.util.stream.Collectors.groupingBy(Device::getStatus, java.util.stream.Collectors.counting()));
    }

    /** 已配置 SNMP 的设备数量（用于 SNMP/Redis 诊断） */
    public int countSnmpConfigured() {
        return deviceRepository.findByDeletedFalseAndSnmpConfigured().size();
    }
}
