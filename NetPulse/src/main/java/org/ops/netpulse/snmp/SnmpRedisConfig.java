package org.ops.netpulse.snmp;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 当 SNMP 采集启用且需写入 Redis 时，提供独立的 Redis 连接（支持密码与超时）。
 * 使用 spring.data.redis 配置，与 Docker Redis 一致。
 */
@Configuration
@ConditionalOnProperty(name = "snmp.collect.enabled", havingValue = "true")
public class SnmpRedisConfig {

    @Value("${spring.data.redis.host:192.168.1.160}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:root123456}")
    private String password;

    @Value("${spring.data.redis.timeout:3000}")
    private long timeoutMs;

    @Bean(name = "snmpRedisConnectionFactory")
    public RedisConnectionFactory snmpRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeoutMs))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5000)))
                                .build())
                        .build())
                .build();
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean(name = "snmpStringRedisTemplate")
    public StringRedisTemplate snmpStringRedisTemplate(RedisConnectionFactory snmpRedisConnectionFactory) {
        return new StringRedisTemplate(snmpRedisConnectionFactory);
    }
}
