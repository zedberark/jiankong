package org.ops.netpulse.service;

/**
 * 网络设备 CPU/内存等指标来源合并：<strong>优先 SNMP（Redis）</strong>，无效或过期时再采用
 * <strong>SSH/Telnet 厂商命令</strong>缓存；SSH 命中时仍可合并 SNMP 侧的流量累计字段。
 */
public final class NetworkDeviceStatsMerger {

    /** 与 {@link org.ops.netpulse.controller.MetricsController} 实时接口默认一致 */
    public static final long DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L;

    private NetworkDeviceStatsMerger() {}

    /**
     * @param now       当前毫秒时间
     * @param maxAgeMs  updatedAt 与 now 差超过此值视为过期
     * @param snmpStats 来自 Redis（SNMP 写入），可为 null
     * @param sshStats  来自 {@link DeviceSshCollectService} 内存缓存，可为 null
     * @return stats 为 null 表示无可用数据；source 为 {@code snmp}、{@code ssh} 或 null
     */
    public static Pick pick(long now, long maxAgeMs, DeviceStatsService.DeviceStats snmpStats, DeviceStatsService.DeviceStats sshStats) {
        if (maxAgeMs <= 0) maxAgeMs = DEFAULT_MAX_AGE_MS;
        if (fresh(snmpStats, now, maxAgeMs) && hasCpuOrMem(snmpStats)) {
            return new Pick(copy(snmpStats), "snmp");
        }
        if (fresh(sshStats, now, maxAgeMs) && hasCpuOrMem(sshStats)) {
            DeviceStatsService.DeviceStats c = copy(sshStats);
            if (fresh(snmpStats, now, maxAgeMs) && snmpStats != null) {
                mergeTrafficFields(snmpStats, c);
            }
            return new Pick(c, "ssh");
        }
        if (fresh(snmpStats, now, maxAgeMs) && snmpStats != null && hasTraffic(snmpStats)) {
            return new Pick(copy(snmpStats), "snmp");
        }
        return new Pick(null, null);
    }

    public record Pick(DeviceStatsService.DeviceStats stats, String source) {}

    private static boolean fresh(DeviceStatsService.DeviceStats s, long now, long maxAgeMs) {
        if (s == null || s.getUpdatedAt() <= 0) return false;
        return now - s.getUpdatedAt() <= maxAgeMs;
    }

    private static boolean hasCpuOrMem(DeviceStatsService.DeviceStats s) {
        return s != null && (s.getCpuPercent() != null || s.getMemoryPercent() != null);
    }

    private static boolean hasTraffic(DeviceStatsService.DeviceStats s) {
        if (s == null) return false;
        String in = s.getIfInOctetsTotal();
        String out = s.getIfOutOctetsTotal();
        return (in != null && !in.isBlank()) || (out != null && !out.isBlank());
    }

    private static DeviceStatsService.DeviceStats copy(DeviceStatsService.DeviceStats s) {
        if (s == null) return null;
        DeviceStatsService.DeviceStats c = new DeviceStatsService.DeviceStats();
        c.setCpuPercent(s.getCpuPercent());
        c.setMemoryPercent(s.getMemoryPercent());
        c.setDiskPercent(s.getDiskPercent());
        c.setUpdatedAt(s.getUpdatedAt());
        c.setIfInOctetsTotal(s.getIfInOctetsTotal());
        c.setIfOutOctetsTotal(s.getIfOutOctetsTotal());
        return c;
    }

    private static void mergeTrafficFields(DeviceStatsService.DeviceStats fromSnmp, DeviceStatsService.DeviceStats into) {
        if (fromSnmp == null || into == null) return;
        String in = fromSnmp.getIfInOctetsTotal();
        String out = fromSnmp.getIfOutOctetsTotal();
        if (in != null && !in.isBlank()) into.setIfInOctetsTotal(in);
        if (out != null && !out.isBlank()) into.setIfOutOctetsTotal(out);
    }
}
