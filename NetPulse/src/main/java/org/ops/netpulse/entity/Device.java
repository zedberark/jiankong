package org.ops.netpulse.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 设备表，对应 nebula_ops.device。
 * Web SSH 需在库中增加 ssh_user、ssh_password 列（或由 ddl-auto=update 自动添加）。
 */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 45)
    private String ip;

    @Convert(converter = Device.DeviceTypeConverter.class)
    @Column(columnDefinition = "ENUM('router','switch','server','firewall','other') DEFAULT 'server'")
    private DeviceType type = DeviceType.server;

    @Column(length = 50)
    private String vendor;

    @Column(length = 50)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "snmp_version", columnDefinition = "ENUM('v1','v2c','v3') DEFAULT 'v2c'")
    private SnmpVersion snmpVersion = SnmpVersion.v2c;

    @Column(name = "snmp_community", length = 50)
    private String snmpCommunity;

    @Column(name = "snmp_security", columnDefinition = "TEXT")
    private String snmpSecurity;

    @Column(name = "snmp_port")
    private Integer snmpPort = 161;

    @Column(name = "ssh_port")
    private Integer sshPort = 22;

    /** Web SSH 用；若表结构未包含此列，需 ALTER 或使用 ddl-auto=update */
    @Column(name = "ssh_user", length = 64)
    private String sshUser;

    @Column(name = "ssh_password", length = 256)
    private String sshPassword;

    @Column(length = 512)
    private String remark;

    @Column(name = "group_name", length = 64)
    private String groupName;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('normal','warning','critical','offline') DEFAULT 'normal'")
    private DeviceStatus status = DeviceStatus.normal;

    @Column(name = "last_poll_time")
    private LocalDateTime lastPollTime;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(nullable = false)
    private Boolean deleted = false;

    /** 库存 switch，枚举用 sw，与 JSON/API 一致 */
    @jakarta.persistence.Converter
    public static class DeviceTypeConverter implements jakarta.persistence.AttributeConverter<DeviceType, String> {
        @Override
        public String convertToDatabaseColumn(DeviceType attribute) {
            if (attribute == null) return null;
            return attribute == DeviceType.sw ? "switch" : attribute.name();
        }
        @Override
        public DeviceType convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty()) return null;
            if ("switch".equalsIgnoreCase(dbData)) return DeviceType.sw;
            try { return DeviceType.valueOf(dbData); } catch (IllegalArgumentException e) { return DeviceType.other; }
        }
    }

    public enum DeviceType {
        router, sw, server, firewall, other;  // sw <-> DB/JSON 'switch'
        @JsonValue
        public String toValue() { return this == sw ? "switch" : name(); }
        @JsonCreator
        public static DeviceType fromValue(String v) {
            if (v == null || v.isEmpty()) return null;
            if ("switch".equalsIgnoreCase(v)) return sw;
            try { return valueOf(v); } catch (Exception e) { return other; }
        }
    }
    public enum SnmpVersion { v1, v2c, v3 }
    public enum DeviceStatus { normal, warning, critical, offline }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
