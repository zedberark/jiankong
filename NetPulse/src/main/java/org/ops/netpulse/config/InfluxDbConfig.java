package org.ops.netpulse.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfluxDbConfig {

    @Value("${influxdb.url:http://localhost:8086}")
    private String url;

    @Value("${influxdb.token:}")
    private String token;

    @Value("${influxdb.org:netpulse}")
    private String org;

    @Bean
    @ConditionalOnProperty(name = "influxdb.token")
    public InfluxDBClient influxDBClient() {
        if (token == null || token.isBlank()) return null;
        return InfluxDBClientFactory.create(url, token.toCharArray(), org);
    }
}
