package org.ops.netpulse.controller;

import org.ops.netpulse.service.InfluxDbMetricService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CPU/内存时序接口，供静态页 metric-chart.html 及 README 中 GET /api/metric/cpu-mem 使用。
 * 数据来自 InfluxDB device_metric 结构。
 */
@RestController
@RequestMapping("/metric")
@CrossOrigin
public class MetricController {

    private final InfluxDbMetricService influxDbMetricService;

    public MetricController(InfluxDbMetricService influxDbMetricService) {
        this.influxDbMetricService = influxDbMetricService;
    }

    @GetMapping("/cpu-mem")
    public ResponseEntity<Map<String, ?>> getCpuMem(
            @RequestParam(defaultValue = "1") Long deviceId,
            @RequestParam(defaultValue = "1") int hours
    ) {
        Map<String, ?> data = influxDbMetricService.queryCpuMemData(String.valueOf(deviceId), hours);
        return ResponseEntity.ok(data);
    }
}
