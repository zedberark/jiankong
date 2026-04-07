package org.ops.netpulse.service;

import org.ops.netpulse.entity.Device;
import org.ops.netpulse.service.DeviceStatsService.DeviceStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 从 Redis 读取设备 CPU/内存，供实时指标页展示。
 * 支持两种 Key 格式：
 * 1) device:{name}（如 device:R1）：Hash 含 ip、cpu、mem、collect_time，按设备 IP 匹配；
 * 2) netpulse:snmp:device:ip:{ip}：Hash 含 cpu、memory、lastCollectTime。
 */
@Service
public class SnmpStatsService {

    /** 外部/EVE 等写入：device:R1，Hash 字段 ip、cpu、mem、collect_time */
    private static final String REDIS_DEVICE_PREFIX = "device:";
    private static final String REDIS_KEY_PREFIX = "netpulse:snmp:device";
    private static final String REDIS_KEY_PREFIX_IP = REDIS_KEY_PREFIX + ":ip:";

    private final StringRedisTemplate redisTemplate;

    public SnmpStatsService(
            @Qualifier("snmpStringRedisTemplate") @Autowired(required = false) StringRedisTemplate snmpTemplate,
            @Autowired(required = false) StringRedisTemplate defaultTemplate) {
        this.redisTemplate = snmpTemplate != null ? snmpTemplate : defaultTemplate;
    }

    /**
     * 按设备 IP 从 Redis 读取 CPU、内存。
     * 优先从 device:*（如 device:R1）读取：扫描 key，用 Hash 中的 ip 匹配设备，取 cpu、mem、collect_time；
     * 未命中时再试 netpulse:snmp:device:ip:{ip}（字段 cpu、memory、lastCollectTime）。
     */
    public Map<Long, DeviceStats> getStatsFromRedisByIp(List<Device> devices) {
        Map<Long, DeviceStats> out = new HashMap<>();
        if (redisTemplate == null || devices == null || devices.isEmpty()) return out;

        // 1) 从 device:* 按 IP 构建 ip -> DeviceStats（EVE/外部写入格式：ip、cpu、mem、collect_time）
        Map<String, DeviceStats> byIp = buildStatsFromDeviceKeys();
        for (Device d : devices) {
            if (d.getId() == null) continue;
            String ip = d.getIp();
            if (ip == null || ip.isBlank()) continue;
            DeviceStats s = byIp.get(ip.trim());
            if (s != null) {
                out.put(d.getId(), s);
            }
        }

        // 2) 未命中的设备再试 netpulse:snmp:device:ip:{ip}，用 pipeline 一次查完
        List<Device> missing = new ArrayList<>();
        for (Device d : devices) {
            if (d.getId() != null && !out.containsKey(d.getId()) && d.getIp() != null && !d.getIp().isBlank())
                missing.add(d);
        }
        if (!missing.isEmpty()) {
            try {
                List<String> ipKeys = new ArrayList<>(missing.size());
                for (Device d : missing) ipKeys.add(REDIS_KEY_PREFIX_IP + d.getIp().trim());
                List<Object> fallbackResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (String key : ipKeys) {
                        connection.hashCommands().hGetAll(key.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
                for (int i = 0; i < missing.size() && i < fallbackResults.size(); i++) {
                    Object obj = fallbackResults.get(i);
                    if (!(obj instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<byte[], byte[]> map = (Map<byte[], byte[]>) obj;
                    String cpu = getUtf8(map, "cpu");
                    String memory = getUtf8(map, "memory");
                    String lastCollect = getUtf8(map, "lastCollectTime");
                    long updatedAt = parseCollectTime(lastCollect);
                    Double cpuPercent = parsePercent(cpu);
                    Double memPercent = parsePercent(memory);
                    // 有任意有效数值则展示；若仅有 lastCollectTime（采集过但解析失败）也返回，便于前端显示「已采集、无解析结果」
                    if (cpuPercent != null || memPercent != null || (lastCollect != null && !lastCollect.isBlank())) {
                        DeviceStats s = new DeviceStats();
                        s.setCpuPercent(cpuPercent);
                        s.setMemoryPercent(memPercent);
                        s.setUpdatedAt(updatedAt > 0 ? updatedAt : System.currentTimeMillis());
                        out.put(missing.get(i).getId(), s);
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** 扫描 Redis key device:*，用 Pipeline 一次取回所有 HGETALL，减少虚拟机/Docker Redis 往返，按 ip 构建 DeviceStats */
    private Map<String, DeviceStats> buildStatsFromDeviceKeys() {
        Map<String, DeviceStats> byIp = new HashMap<>();
        if (redisTemplate == null) return byIp;
        try {
            Set<String> keySet = redisTemplate.keys(REDIS_DEVICE_PREFIX + "*");
            if (keySet == null || keySet.isEmpty()) return byIp;
            List<String> keys = new ArrayList<>(keySet);
            List<Object> rawResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : keys) {
                    connection.hashCommands().hGetAll(key.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            for (int i = 0; i < keys.size() && i < rawResults.size(); i++) {
                Object obj = rawResults.get(i);
                if (!(obj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<byte[], byte[]> map = (Map<byte[], byte[]>) obj;
                String ip = getUtf8(map, "ip");
                if (ip == null || ip.isBlank()) continue;
                String cpu = getUtf8(map, "cpu");
                String mem = getUtf8(map, "mem");
                String collectTime = getUtf8(map, "collect_time");
                long updatedAt = parseCollectTime(collectTime);
                Double cpuPercent = parsePercent(cpu);
                Double memPercent = parsePercent(mem);
                if (cpuPercent != null || memPercent != null) {
                    DeviceStats s = new DeviceStats();
                    s.setCpuPercent(cpuPercent);
                    s.setMemoryPercent(memPercent);
                    s.setUpdatedAt(updatedAt > 0 ? updatedAt : System.currentTimeMillis());
                    byIp.put(ip.trim(), s);
                }
            }
        } catch (Exception ignored) {}
        return byIp;
    }

    /**
     * 从 Redis pipeline 返回的 HGETALL 结果中读取字段值。
     * 兼容两种情况：
     * 1）底层连接返回 Map<byte[], byte[]>（无序列化）；
     * 2）被 RedisTemplate 序列化为 Map<String, String>。
     */
    @SuppressWarnings("unchecked")
    private static String getUtf8(Map<?, ?> map, String field) {
        if (map == null) return null;
        // 情况 1：Map<byte[], byte[]>
        if (!map.isEmpty() && map.keySet().iterator().next() instanceof byte[]) {
            Map<byte[], byte[]> bytesMap = (Map<byte[], byte[]>) map;
            byte[] k = field.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<byte[], byte[]> e : bytesMap.entrySet()) {
                if (Arrays.equals(k, e.getKey())) {
                    return e.getValue() == null ? null : new String(e.getValue(), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
        // 情况 2：Map<String, String>
        if (!map.isEmpty() && map.keySet().iterator().next() instanceof String) {
            Map<String, String> strMap = (Map<String, String>) map;
            Object v = strMap.get(field);
            return v != null ? v.toString() : null;
        }
        return null;
    }

    private static long parseCollectTime(Object v) {
        if (v == null) return 0;
        String str = v.toString().trim();
        if (str.isEmpty()) return 0;
        try {
            long t = Long.parseLong(str);
            // 10 位为秒，13 位为毫秒
            return t < 1_000_000_000_000L ? t * 1000 : t;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从 Redis 按设备 ID 读取（兼容旧 key netpulse:snmp:device:{deviceId}）。
     * 新部署请使用 getStatsFromRedisByIp(List&lt;Device&gt;)。
     */
    public Map<Long, DeviceStats> getStatsFromRedis(List<Long> deviceIds) {
        Map<Long, DeviceStats> out = new HashMap<>();
        if (redisTemplate == null || deviceIds == null || deviceIds.isEmpty()) return out;
        for (Long id : deviceIds) {
            String key = REDIS_KEY_PREFIX + ":" + id;
            String cpu = redisTemplate.opsForHash().get(key, "cpu") != null
                    ? (String) redisTemplate.opsForHash().get(key, "cpu") : null;
            String memory = redisTemplate.opsForHash().get(key, "memory") != null
                    ? (String) redisTemplate.opsForHash().get(key, "memory") : null;
            long updatedAt = parseCollectTime(redisTemplate.opsForHash().get(key, "lastCollectTime"));
            Double cpuPercent = parsePercent(cpu);
            Double memPercent = parsePercent(memory);
            if (cpuPercent != null || memPercent != null) {
                DeviceStats s = new DeviceStats();
                s.setCpuPercent(cpuPercent);
                s.setMemoryPercent(memPercent);
                s.setUpdatedAt(updatedAt > 0 ? updatedAt : System.currentTimeMillis());
                out.put(id, s);
            }
        }
        return out;
    }

    /** 诊断用：当前 Redis 中设备 key 数量（device:* + netpulse:snmp:device:*） */
    public int countSnmpKeysInRedis() {
        if (redisTemplate == null) return -1;
        try {
            Set<String> deviceKeys = redisTemplate.keys(REDIS_DEVICE_PREFIX + "*");
            Set<String> snmpKeys = redisTemplate.keys(REDIS_KEY_PREFIX + ":*");
            int a = deviceKeys != null ? deviceKeys.size() : 0;
            int b = snmpKeys != null ? snmpKeys.size() : 0;
            return a + b;
        } catch (Exception e) {
            return -1;
        }
    }

    private static Double parsePercent(String v) {
        if (v == null || v.isEmpty() || "-".equals(v.trim())) return null;
        try {
            double d = Double.parseDouble(v.trim());
            if (d >= 0 && d <= 100) return d;
        } catch (NumberFormatException ignored) {}
        return null;
    }
}
