package org.ops.netpulse.snmp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 早期「单台固定主机」SNMPv3 采集（snmp.firewall.*）。
 * 无固定防火墙时无需启用；网络设备与防火墙均在「设备管理」中按设备配置（与锐捷一致）。
 * 仅当 snmp.firewall.enabled=true 时本 Bean 才加载。
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "snmp.firewall.enabled", havingValue = "true")
public class SnmpCollectService {

    private final StringRedisTemplate redisTemplate;

    @Value("${snmp.firewall.host:192.168.1.160}")
    private String host;

    @Value("${snmp.firewall.port:161}")
    private int port;

    @Value("${snmp.firewall.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${snmp.firewall.retries:2}")
    private int retries;

    @Value("${snmp.firewall.username:snmpuser}")
    private String username;

    @Value("${snmp.firewall.auth-password:Auth@123456}")
    private String authPassword;

    @Value("${snmp.firewall.priv-password:Priv@123456}")
    private String privPassword;

    @Value("${snmp.redis.key-prefix:netpulse:snmp:firewall}")
    private String redisKeyPrefix;

    public SnmpCollectService(@Qualifier("snmpStringRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static final String REDIS_KEY_DATA = "data";
    private static final String REDIS_KEY_LAST_TIME = "lastCollectTime";

    /**
     * 每 5 分钟采集一次（可通过 snmp.collect.interval-ms 配置），
     * 采集 1.3.6.1.2.1.1.5.0、1.3.6.1.2.1.1.1.0、1.3.6.1.2.1.2.1.0 并写入 Redis。
     */
    @Scheduled(
            initialDelayString = "${snmp.collect.interval-ms:300000}",
            fixedDelayString = "${snmp.collect.interval-ms:300000}"
    )
    public void collectAndSaveToRedis() {
        String baseKey = redisKeyPrefix;
        try {
            Map<String, String> result = SnmpUtils.snmpV3Get(
                    host,
                    port,
                    timeoutMs,
                    retries,
                    username,
                    authPassword,
                    privPassword,
                    SnmpUtils.OID_SYS_NAME,
                    SnmpUtils.OID_SYS_DESCR,
                    SnmpUtils.OID_IF_NUMBER
            );

            if (result.isEmpty()) {
                log.warn("SNMP 采集结果为空，设备 {}:{}", host, port);
                return;
            }

            try {
                String dataKey = baseKey + ":" + REDIS_KEY_DATA;
                // 覆盖写入：先删后写，不追加
                redisTemplate.delete(dataKey);
                redisTemplate.opsForHash().put(dataKey, "1.3.6.1.2.1.1.5.0", result.getOrDefault(SnmpUtils.OID_SYS_NAME, "-"));
                redisTemplate.opsForHash().put(dataKey, "1.3.6.1.2.1.1.1.0", result.getOrDefault(SnmpUtils.OID_SYS_DESCR, "-"));
                redisTemplate.opsForHash().put(dataKey, "1.3.6.1.2.1.2.1.0", result.getOrDefault(SnmpUtils.OID_IF_NUMBER, "-"));
                redisTemplate.opsForHash().put(dataKey, "sysName", result.getOrDefault(SnmpUtils.OID_SYS_NAME, "-"));
                redisTemplate.opsForHash().put(dataKey, "sysDescr", result.getOrDefault(SnmpUtils.OID_SYS_DESCR, "-"));
                redisTemplate.opsForHash().put(dataKey, "ifNumber", result.getOrDefault(SnmpUtils.OID_IF_NUMBER, "-"));
                redisTemplate.opsForValue().set(baseKey + ":" + REDIS_KEY_LAST_TIME, String.valueOf(Instant.now().toEpochMilli()));

                log.info("SNMP 采集成功 {}:{} | sysName={} | ifNumber={} | 已写入 Redis key={}",
                        host, port,
                        result.getOrDefault(SnmpUtils.OID_SYS_NAME, "-"),
                        result.getOrDefault(SnmpUtils.OID_IF_NUMBER, "-"),
                        dataKey);
            } catch (Exception e) {
                log.error("SNMP 采集成功但写入 Redis 失败（请检查 Redis 连接）: {}", e.getMessage());
            }
        } catch (SnmpUtils.SnmpException e) {
            log.error("SNMP 采集失败 {}:{} [SNMPv3] | {} (排查：snmp.firewall 用户名/认证/加密密码与设备一致、防火墙放行 UDP 161、可增大 snmp.firewall.timeout-ms)", host, port, e.getMessage());
        } catch (Exception e) {
            log.error("SNMP 采集异常 {}:{}", host, port, e);
        }
    }
}
