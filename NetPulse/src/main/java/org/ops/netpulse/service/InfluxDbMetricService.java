package org.ops.netpulse.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.ops.netpulse.dto.MetricData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 InfluxDB 读取 device_metric 时序数据，供 CPU/内存折线图使用。
 * 数据格式：measurement=device_metric, device_id=*, metric_key=cpu_usage|mem_usage, field=value。
 */
@Service
public class InfluxDbMetricService {

    private static final Logger log = LoggerFactory.getLogger(InfluxDbMetricService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.org:nebula_org}")
    private String org;
    @Value("${influxdb.bucket:device_metrics}")
    private String bucket;

    public InfluxDbMetricService(@Autowired(required = false) InfluxDBClient influxDBClient) {
        this.influxDBClient = influxDBClient;
    }

    /**
     * 查询指定指标的时序数据（device_metric 结构）
     *
     * @param metricKey 指标键：cpu_usage / mem_usage
     * @param deviceId  设备 ID（tag device_id）
     * @param hours     最近 N 小时
     * @return 按时间正序的时序点
     */
    public List<MetricData> queryMetricTimeSeries(String metricKey, String deviceId, int hours) {
        List<MetricData> result = new ArrayList<>();
        if (influxDBClient == null) return result;

        String flux = String.format("""
                from(bucket: "%s")
                |> range(start: -%dh)
                |> filter(fn: (r) => r._measurement == "device_metric")
                |> filter(fn: (r) => r._field == "value")
                |> filter(fn: (r) => r.metric_key == "%s")
                |> filter(fn: (r) => r.device_id == "%s")
                |> sort(columns: ["_time"], desc: false)
                """, bucket, hours, metricKey.replace("\"", "\\\""), deviceId.replace("\"", "\\\""));

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(flux, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant instant = record.getTime();
                    LocalDateTime ldt = LocalDateTime.ofInstant(instant != null ? instant : Instant.EPOCH, ZoneId.systemDefault());
                    String timeStr = ldt.format(DATE_FORMATTER);
                    Object v = record.getValue();
                    double value = (v instanceof Number) ? ((Number) v).doubleValue() : 0.0;
                    result.add(new MetricData(timeStr, value));
                }
            }
        } catch (Exception e) {
            log.warn("InfluxDB query metric failed: metricKey={}, deviceId={}, hours={}", metricKey, deviceId, hours, e);
        }
        return result;
    }

    /**
     * 批量查询 CPU + 内存时序数据
     */
    public Map<String, List<MetricData>> queryCpuMemData(String deviceId, int hours) {
        List<MetricData> cpu = queryMetricTimeSeries("cpu_usage", deviceId, hours);
        List<MetricData> mem = queryMetricTimeSeries("mem_usage", deviceId, hours);
        return Map.of("cpu", cpu, "mem", mem);
    }
}
