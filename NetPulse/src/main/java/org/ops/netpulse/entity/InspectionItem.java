package org.ops.netpulse.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "inspection_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    @JsonIgnore
    private InspectionReport report;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "device_type", length = 32)
    private String deviceType;

    @Column(name = "rtt_ms")
    private Long rttMs;

    /** normal / warning / offline，与 MonitorService 可达性判定一致 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
