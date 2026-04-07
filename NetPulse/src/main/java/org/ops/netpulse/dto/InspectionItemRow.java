package org.ops.netpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 巡检明细行（API 输出，无循环引用） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InspectionItemRow {
    private Long id;
    private Long deviceId;
    private String deviceName;
    private String ip;
    private String deviceType;
    private Long rttMs;
    /** normal / warning / offline */
    private String status;
}
