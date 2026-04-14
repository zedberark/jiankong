package org.ops.netpulse.repository;

import org.ops.netpulse.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** 仅更新状态与最后轮询时间，不覆盖 name 等字段，避免与「获取主机名」等并发更新冲突 */
    @Modifying
    @Query("UPDATE Device d SET d.status = :status, d.lastPollTime = :lastPollTime WHERE d.id = :id")
    int updateStatusAndLastPollTime(@Param("id") Long id, @Param("status") Device.DeviceStatus status, @Param("lastPollTime") LocalDateTime lastPollTime);

    /** 仅更新厂商，避免后台任务用旧实体覆盖设备管理最新配置 */
    @Modifying
    @Query("UPDATE Device d SET d.vendor = :vendor WHERE d.id = :id")
    int updateVendorById(@Param("id") Long id, @Param("vendor") String vendor);

    /** 仅更新厂商与型号，避免后台任务并发覆盖 IP/账号/SNMP 配置 */
    @Modifying
    @Query("UPDATE Device d SET d.vendor = :vendor, d.model = :model WHERE d.id = :id")
    int updateVendorAndModelById(@Param("id") Long id, @Param("vendor") String vendor, @Param("model") String model);

    /** 未删除且用于监控采集的设备 */
    List<Device> findByDeletedFalse();

    List<Device> findByDeletedFalseAndType(Device.DeviceType type);

    List<Device> findByDeletedFalseAndGroupName(String groupName);
    
    /** 按管理 IP 查询未删除设备（用于新增去重） */
    List<Device> findByDeletedFalseAndIp(String ip);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT d.groupName FROM Device d WHERE d.deleted = false AND d.groupName IS NOT NULL AND d.groupName != '' ORDER BY d.groupName")
    List<String> findDistinctGroupNames();

    /** 已配置 SNMP 的设备（v2c 填 community，v3 填 snmp_security JSON） */
    @org.springframework.data.jpa.repository.Query("SELECT d FROM Device d WHERE d.deleted = false AND (d.snmpCommunity IS NOT NULL AND d.snmpCommunity != '' OR d.snmpSecurity IS NOT NULL AND d.snmpSecurity != '')")
    List<Device> findByDeletedFalseAndSnmpConfigured();
}
