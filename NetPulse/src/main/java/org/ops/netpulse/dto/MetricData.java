package org.ops.netpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 监控指标时序数据（适配 ECharts 折线图）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricData {
    /** 时间，格式 yyyy-MM-dd HH:mm:ss */
    private String time;
    /** 指标值（如 CPU/内存使用率） */
    private Double value;
}
