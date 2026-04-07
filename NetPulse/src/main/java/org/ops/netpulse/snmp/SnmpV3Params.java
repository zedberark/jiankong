package org.ops.netpulse.snmp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 设备 SNMPv3 配置，对应 device.snmp_security JSON。
 */
@Data
public class SnmpV3Params {
    private String username;
    @JsonProperty("authPassword")
    private String authPassword;
    @JsonProperty("privPassword")
    private String privPassword;
}
