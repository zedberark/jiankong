package org.ops.netpulse.snmp;

import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.Target;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ops.netpulse.entity.Device;

import java.io.IOException;
import java.util.*;

/**
 * SNMP 工具类：v2c（Community）与 v3（authPriv，SHA + AES-128）。
 * 支持华为防火墙/路由器、锐捷、Cisco 等设备，采集 sysName/sysDescr/ifNumber 及 CPU。
 * CPU/内存 OID 优先按 {@link #sysObjectIdToVendorKey}（IANA 企业号）自动选择，其次 sysDescr/设备表厂商字段。
 */
public final class SnmpUtils {

    /** sysName.0 */
    public static final String OID_SYS_NAME = "1.3.6.1.2.1.1.5.0";
    /** sysDescr.0 */
    public static final String OID_SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    /** sysObjectID.0：用于识别厂商（2011=Huawei，9=Cisco 等） */
    public static final String OID_SYS_OBJECT_ID = "1.3.6.1.2.1.1.2.0";
    /** ifNumber.0 */
    public static final String OID_IF_NUMBER = "1.3.6.1.2.1.2.1.0";
    /** IF-MIB ifHCInOctets 表基 OID（WALK 汇总接口入向字节） */
    public static final String OID_WALK_IF_HC_IN_OCTETS = "1.3.6.1.2.1.31.1.1.1.6";
    /** IF-MIB ifHCOutOctets 表基 OID（WALK 汇总接口出向字节） */
    public static final String OID_WALK_IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10";
    /** IF-MIB ifInOctets 表基 OID（32 位计数，兼容部分不支持 HC 计数器的设备） */
    public static final String OID_WALK_IF_IN_OCTETS = "1.3.6.1.2.1.2.2.1.10";
    /** IF-MIB ifOutOctets 表基 OID（32 位计数，兼容部分不支持 HC 计数器的设备） */
    public static final String OID_WALK_IF_OUT_OCTETS = "1.3.6.1.2.1.2.2.1.16";

    /**
     * 华为部分设备 CPU（sysDescr 识别厂商后优先尝试；与 ENTSYS 分支一致时常见为标量 .0）。
     * 与 2011.5.25.31 / 2011.6.3.17 等并列尝试，适配不同 VRP 版本。
     */
    public static final String OID_CPU_HUAWEI_ENTSYS_SCALAR = "1.3.6.1.4.1.2011.6.1.1.1.3.0";
    /** 华为 VRP CPU 使用率（hwEntityCpuUsage，常见实例索引 1） */
    public static final String OID_CPU_HUAWEI = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5.1";
    /** 华为 VRP CPU 使用率（部分设备实例索引为 0） */
    public static final String OID_CPU_HUAWEI_IDX0 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5.0";
    /**
     * 华为 USG6000 等防火墙（eNSP / V100R001 等）：ENTITY 表项索引常为 <strong>2</strong>，而非 .1。
     * 参考：hwEntityCpuUsage 带 hwEntityPhysicalIndex.2
     */
    public static final String OID_CPU_HUAWEI_USG_ENTITY_2 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5.2";
    /**
     * 华为 USG V500R001 等：实体索引多为 <strong>67108873</strong>（与 sulabs 等文档一致）。
     */
    public static final String OID_CPU_HUAWEI_USG_ENTITY_67108873 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5.67108873";
    /**
     * 华为 AR 系列 CPU 使用率（通过 snmpwalk 验证）：
     * enterprises.2011.6.3.17.1.1.3.0.0.1 = INTEGER: 78  等
     */
    public static final String OID_CPU_HUAWEI_AR = "1.3.6.1.4.1.2011.6.3.17.1.1.3.0.0.1";
    /**
     * 部分旧版 VRP / eNSP 模拟器（如 WVRP-C VRPV500）实现的 CPU 占用，见 HUAWEI-CPU-MIB hwCpuDevUsage。
     */
    public static final String OID_CPU_HUAWEI_HW10 = "1.3.6.1.4.1.2011.10.2.4.1.3.1";
    /** Cisco CPU 5 分钟平均（cpmCPUTotal5minRev 常见实例 .1，教材/设备常配） */
    public static final String OID_CPU_CISCO_5MIN = "1.3.6.1.4.1.9.9.109.1.1.1.1.7.1";
    /** Cisco cpmCPUTotalPhysicalIndex 等（CISCO-PROCESS-MIB） */
    public static final String OID_CPU_CISCO = "1.3.6.1.4.1.9.9.109.1.1.1.1.6";
    /** Cisco OLD-CISCO-CPU-MIB avgBusy5（5 分钟平均 CPU%，常见于 vIOS/IOL） */
    public static final String OID_CPU_CISCO_OLD = "1.3.6.1.4.1.9.2.1.58.0";
    /** H3C Comware 部分型号 CPU 使用率（实例 .1 常见） */
    public static final String OID_CPU_H3C = "1.3.6.1.4.1.25506.2.6.1.1.1.1.3.1";
    public static final String OID_CPU_H3C_ALT = "1.3.6.1.4.1.25506.2.6.1.1.1.1.3";
    /** 锐捷/通用 hrProcessorLoad */
    public static final String OID_CPU_RUIJIE = "1.3.6.1.4.1.4881.1.1.10.2.35.1.1.1.1.8";
    /** 标准 HOST-RESOURCES-MIB hrProcessorLoad（部分设备） */
    public static final String OID_CPU_HR = "1.3.6.1.2.1.25.3.3.1.2.1";

    /** 华为 VRP 内存使用率（百分比，hwEntityMemUsage，常见实例索引 1） */
    public static final String OID_MEM_HUAWEI = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7.1";
    /** 华为部分型号内存百分比 OID（常见于实验环境/机型差异） */
    public static final String OID_MEM_HUAWEI_ALT = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.6.1";
    /** 华为 VRP 内存使用率（部分设备实例索引为 0） */
    public static final String OID_MEM_HUAWEI_IDX0 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7.0";
    /** USG6000 等防火墙（实体索引 2）内存使用率 */
    public static final String OID_MEM_HUAWEI_USG_ENTITY_2 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7.2";
    /** USG V500R001 等（实体索引 67108873）内存使用率 */
    public static final String OID_MEM_HUAWEI_USG_ENTITY_67108873 = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7.67108873";
    /** 旧版 VRP / eNSP 常见内存占用百分比（hwMemoryDevUsage 等，表项 .1） */
    public static final String OID_MEM_HUAWEI_HW10 = "1.3.6.1.4.1.2011.10.2.4.2.1.1.1";
    /** HOST-RESOURCES-MIB hrStorageUsed（索引 1 常为物理内存） */
    public static final String OID_MEM_HR_USED = "1.3.6.1.2.1.25.2.3.1.6.1";
    /** HOST-RESOURCES-MIB hrStorageSize（索引 1） */
    public static final String OID_MEM_HR_SIZE = "1.3.6.1.2.1.25.2.3.1.5.1";
    /** Cisco ciscoMemoryPoolUsed（索引 1，单位字节） */
    public static final String OID_MEM_CISCO_USED = "1.3.6.1.4.1.9.9.48.1.1.1.5.1";
    /** Cisco ciscoMemoryPoolFree（索引 1，单位字节），使用率 = used/(used+free)*100 */
    public static final String OID_MEM_CISCO_FREE = "1.3.6.1.4.1.9.9.48.1.1.1.6.1";

    private static final List<String> CPU_OIDS_ALL = List.of(
            OID_CPU_HUAWEI_ENTSYS_SCALAR,
            OID_CPU_HUAWEI_USG_ENTITY_67108873, OID_CPU_HUAWEI_USG_ENTITY_2,
            OID_CPU_HR, OID_CPU_HUAWEI_HW10, OID_CPU_HUAWEI_AR, OID_CPU_HUAWEI, OID_CPU_HUAWEI_IDX0,
            OID_CPU_H3C, OID_CPU_H3C_ALT,
            OID_CPU_CISCO_5MIN, OID_CPU_CISCO_OLD, OID_CPU_CISCO,
            OID_CPU_RUIJIE,
            OID_CPU_HR
    );
    private static final List<String> MEM_OIDS_PERCENT = List.of(
            OID_MEM_HUAWEI_USG_ENTITY_67108873, OID_MEM_HUAWEI_USG_ENTITY_2,
            OID_MEM_HUAWEI_HW10, OID_MEM_HUAWEI, OID_MEM_HUAWEI_ALT, OID_MEM_HUAWEI_IDX0);
    private static final List<String> MEM_OIDS_HR_PAIR = List.of(OID_MEM_HR_USED, OID_MEM_HR_SIZE);
    private static final List<String> MEM_OIDS_CISCO_PAIR = List.of(OID_MEM_CISCO_USED, OID_MEM_CISCO_FREE);

    private SnmpUtils() {}

    /**
     * 从 sysDescr（RFC1213-MIB 1.3.6.1.2.1.1.1.0）文本识别厂商，各厂商均支持该 OID，无需用户手选。
     */
    public static String detectVendorFromSysDescr(String sysDescr) {
        if (sysDescr == null || sysDescr.isBlank() || "-".equals(sysDescr.trim())) return null;
        String s = sysDescr;
        if (s.contains("H3C") || s.contains("Hangzhou H3C") || s.contains("New H3C")) return "h3c";
        if (s.contains("Huawei") || s.contains("HUAWEI")) return "huawei";
        // eNSP USG6000 等 sysDescr 可能仅含 USG/Secospace 而无 Huawei 字样
        if (s.contains("USG") || s.contains("Secospace")) return "huawei";
        if (s.contains("Ruijie") || s.contains("RUIJIE")) return "ruijie";
        if (s.contains("Cisco") || s.contains("CISCO")) return "cisco";
        return null;
    }

    /**
     * 从 sysObjectID 解析 IANA 私有企业号（OID 形态 {@code 1.3.6.1.4.1.<pen>.…} 中的 {@code pen}）。
     */
    public static Integer extractPrivateEnterpriseNumber(String sysObjectId) {
        if (sysObjectId == null || sysObjectId.isBlank() || "-".equals(sysObjectId.trim())) return null;
        String s = sysObjectId.trim();
        if (s.startsWith(".")) s = s.substring(1);
        String[] p = s.split("\\.");
        for (int i = 0; i + 2 < p.length; i++) {
            if ("4".equals(p[i]) && "1".equals(p[i + 1])) {
                try {
                    return Integer.parseInt(p[i + 2]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 企业号 → 内部厂商键（与 CPU/内存 OID 分支一致）。未收录的企业号返回 {@code null}，走全量 OID 尝试。
     */
    public static String sysObjectIdToVendorKey(String sysObjectId) {
        Integer pen = extractPrivateEnterpriseNumber(sysObjectId);
        if (pen == null) return null;
        return switch (pen) {
            case 9 -> "cisco";
            case 2011 -> "huawei";
            case 25506 -> "h3c";
            case 4881 -> "ruijie";
            case 3902 -> "zte";
            case 2636 -> "juniper";
            default -> null;
        };
    }

    /** 从 sysObjectID（1.3.6.1.2.1.1.2.0）识别厂商：先按企业号，再按 OID 子串（兼容非标准格式）。 */
    public static String detectVendorFromSysObjectId(String sysObjectId) {
        if (sysObjectId == null || sysObjectId.isBlank() || "-".equals(sysObjectId.trim())) return null;
        String fromPen = sysObjectIdToVendorKey(sysObjectId);
        if (fromPen != null) return fromPen;
        String lower = sysObjectId.toLowerCase();
        if (lower.contains(".2011.")) return "huawei";
        if (lower.contains(".25506.")) return "h3c";
        if (lower.contains(".4881.")) return "ruijie";
        if (lower.contains(".3902.")) return "zte";
        if (lower.contains(".2636.")) return "juniper";
        if (lower.contains(".9.")) return "cisco";
        return null;
    }

    /**
     * 选择 CPU/内存 OID 时的厂商键：优先 sysDescr → sysObjectID（含企业号）→ 设备表 vendor。
     */
    public static String resolveVendorForOids(String sysDescr, String sysObjectId, String existingVendor) {
        String a = detectVendorFromSysDescr(sysDescr);
        if (a != null) return a;
        String b = detectVendorFromSysObjectId(sysObjectId);
        if (b != null) return b;
        if (existingVendor != null && !existingVendor.isBlank()) return existingVendor.trim().toLowerCase();
        return null;
    }

    private static final ObjectMapper SNMP_PROBE_JSON = new ObjectMapper();

    /**
     * 通过一次 SNMP GET（sysDescr、sysObjectID）自动识别厂商键，逻辑与 {@link #resolveVendorForOids} 一致。
     * 失败（超时、无响应、无法识别）返回 {@code null}，不抛异常。
     */
    public static String probeVendorKey(Device d, int timeoutMs, int retries) {
        if (d == null || d.getIp() == null || d.getIp().isBlank()) return null;
        int port = d.getSnmpPort() != null ? d.getSnmpPort() : 161;
        String ip = d.getIp().trim();
        String[] oids = { OID_SYS_DESCR, OID_SYS_OBJECT_ID };
        try {
            Map<String, String> r;
            boolean v3 = d.getSnmpVersion() == Device.SnmpVersion.v3
                    && d.getSnmpSecurity() != null && !d.getSnmpSecurity().isBlank();
            if (v3) {
                SnmpV3Params p = SNMP_PROBE_JSON.readValue(d.getSnmpSecurity(), SnmpV3Params.class);
                if (p.getUsername() == null || p.getUsername().isBlank()) return null;
                r = snmpV3Get(ip, port, timeoutMs, retries,
                        p.getUsername(),
                        p.getAuthPassword() != null ? p.getAuthPassword() : "",
                        p.getPrivPassword() != null ? p.getPrivPassword() : "",
                        oids);
            } else {
                String comm = (d.getSnmpCommunity() != null && !d.getSnmpCommunity().isBlank())
                        ? d.getSnmpCommunity() : "public";
                r = snmpV2cGet(ip, port, comm, timeoutMs, retries, oids);
            }
            String descr = pickSnmpScalar(r, OID_SYS_DESCR);
            String obj = pickSnmpScalar(r, OID_SYS_OBJECT_ID);
            if (descr == null) descr = "-";
            if (obj == null) obj = "-";
            if ("-".equals(descr) && "-".equals(obj)) return null;
            return resolveVendorForOids(descr, obj, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickSnmpScalar(Map<String, String> r, String oid) {
        if (r == null || oid == null) return null;
        String want = normalizeOidKey(oid);
        String v = r.get(oid);
        if (isUsableSnmpValue(v)) return v.trim();
        v = r.get(want);
        if (isUsableSnmpValue(v)) return v.trim();
        for (Map.Entry<String, String> e : r.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (normalizeOidKey(e.getKey()).equals(want) && isUsableSnmpValue(e.getValue())) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    private static boolean isUsableSnmpValue(String v) {
        if (v == null) return false;
        String t = v.trim();
        if (t.isEmpty() || "-".equals(t)) return false;
        String low = t.toLowerCase(Locale.ROOT);
        return !low.contains("nosuchobject") && !low.contains("nosuchinstance");
    }

    /**
     * 将内部厂商键转为界面常用中文/品牌名；已是中文则原样返回。
     */
    public static String vendorKeyToUiLabel(String vendorKey) {
        if (vendorKey == null || vendorKey.isBlank()) return null;
        String raw = vendorKey.trim();
        if (raw.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF)) {
            return raw;
        }
        String k = raw.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "huawei" -> "华为";
            case "h3c" -> "H3C";
            case "cisco" -> "思科";
            case "ruijie" -> "锐捷";
            case "zte" -> "中兴";
            case "juniper" -> "Juniper";
            default -> raw;
        };
    }

    private static List<String> cpuOidsForResolvedVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) return List.of();
        String v = vendor.toLowerCase();
        if (v.contains("huawei") || v.contains("华为"))
            return List.of(OID_CPU_HUAWEI_ENTSYS_SCALAR,
                    OID_CPU_HUAWEI_USG_ENTITY_67108873, OID_CPU_HUAWEI_USG_ENTITY_2,
                    OID_CPU_HR, OID_CPU_HUAWEI_HW10, OID_CPU_HUAWEI_AR, OID_CPU_HUAWEI, OID_CPU_HUAWEI_IDX0);
        if (v.contains("h3c") || v.contains("华三"))
            return List.of(OID_CPU_H3C, OID_CPU_H3C_ALT, OID_CPU_HR);
        if (v.contains("cisco"))
            return List.of(OID_CPU_CISCO_5MIN, OID_CPU_CISCO_OLD, OID_CPU_CISCO, OID_CPU_HR);
        if (v.contains("ruijie") || v.contains("锐捷")) return List.of(OID_CPU_RUIJIE, OID_CPU_HR);
        if ("zte".equals(v) || v.contains("中兴")) return List.of(OID_CPU_HR, OID_CPU_H3C, OID_CPU_H3C_ALT);
        if ("juniper".equals(v)) return List.of(OID_CPU_HR);
        return List.of();
    }

    private static List<String> defaultMemoryOidUnion() {
        List<String> out = new ArrayList<>();
        out.addAll(MEM_OIDS_PERCENT);
        out.addAll(MEM_OIDS_CISCO_PAIR);
        out.addAll(MEM_OIDS_HR_PAIR);
        return out;
    }

    private static List<String> memoryOidsForResolvedVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) return List.of();
        String v = vendor.toLowerCase();
        List<String> out = new ArrayList<>();
        if (v.contains("huawei") || v.contains("华为")) {
            out.add(OID_MEM_HUAWEI_USG_ENTITY_67108873);
            out.add(OID_MEM_HUAWEI_USG_ENTITY_2);
            out.add(OID_MEM_HUAWEI_HW10);
            out.add(OID_MEM_HUAWEI);
            out.add(OID_MEM_HUAWEI_ALT);
            out.add(OID_MEM_HUAWEI_IDX0);
            out.addAll(MEM_OIDS_HR_PAIR);
            return out;
        }
        if (v.contains("h3c") || v.contains("华三")) {
            out.addAll(MEM_OIDS_HR_PAIR);
            return out;
        }
        if (v.contains("cisco")) {
            out.addAll(MEM_OIDS_CISCO_PAIR);
            out.addAll(MEM_OIDS_HR_PAIR);
            return out;
        }
        if (v.contains("ruijie") || v.contains("锐捷")) {
            out.addAll(MEM_OIDS_HR_PAIR);
            return out;
        }
        if ("zte".equals(v) || v.contains("中兴") || "juniper".equals(v)) {
            out.addAll(MEM_OIDS_HR_PAIR);
            return out;
        }
        return List.of();
    }

    /**
     * 按厂商与 {@code sysObjectID} 返回待尝试的 CPU OID 列表。
     * 厂商未知时，根据 {@code sysObjectID} 的企业号将对应厂商 OID 前置，再拼接全量列表（去重保序）。
     */
    public static List<String> getCpuOidsToTry(String vendor, String type, String sysObjectId) {
        List<String> specific = cpuOidsForResolvedVendor(vendor);
        if (!specific.isEmpty()) {
            return new ArrayList<>(specific);
        }
        String fromOid = sysObjectIdToVendorKey(sysObjectId);
        List<String> head = cpuOidsForResolvedVendor(fromOid);
        if (!head.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(head);
            for (String o : CPU_OIDS_ALL) merged.add(o);
            return new ArrayList<>(merged);
        }
        return new ArrayList<>(CPU_OIDS_ALL);
    }

    /** 兼容旧调用：无 sysObjectID 时等价于无法按企业号前置。 */
    public static List<String> getCpuOidsToTry(String vendor, String type) {
        return getCpuOidsToTry(vendor, type, null);
    }

    /**
     * 内存 OID：已知厂商用专属列表；否则按 {@code sysObjectID} 企业号前置，再并上默认并集。
     */
    public static List<String> getMemoryOidsToTry(String vendor, String type, String sysObjectId) {
        List<String> specific = memoryOidsForResolvedVendor(vendor);
        if (!specific.isEmpty()) {
            return new ArrayList<>(specific);
        }
        String fromOid = sysObjectIdToVendorKey(sysObjectId);
        List<String> head = memoryOidsForResolvedVendor(fromOid);
        if (!head.isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(head);
            for (String o : defaultMemoryOidUnion()) merged.add(o);
            return new ArrayList<>(merged);
        }
        return defaultMemoryOidUnion();
    }

    public static List<String> getMemoryOidsToTry(String vendor, String type) {
        return getMemoryOidsToTry(vendor, type, null);
    }

    /**
     * SNMPv2c GET，适用于锐捷、部分路由器等（Community 认证）。
     */
    public static Map<String, String> snmpV2cGet(
            String host,
            int port,
            String community,
            int timeoutMs,
            int retries,
            String... oids
    ) throws SnmpException {
        if (host == null || host.isBlank()) throw new SnmpException("SNMP host 不能为空");
        if (oids == null || oids.length == 0) return new HashMap<>();
        String comm = (community == null || community.isBlank()) ? "public" : community;

        UdpAddress targetAddress = new UdpAddress(host + "/" + port);
        TransportMapping<UdpAddress> transport = null;
        Snmp snmp = null;
        try {
            transport = new DefaultUdpTransportMapping();
            transport.listen();
            snmp = new Snmp(transport);
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(comm));
            target.setAddress(targetAddress);
            target.setRetries(retries);
            target.setTimeout(timeoutMs);
            target.setVersion(SnmpConstants.version2c);

            PDU pdu = new PDU();
            pdu.setType(PDU.GET);
            for (String oid : oids) {
                if (oid != null && !oid.isBlank()) pdu.add(new VariableBinding(new OID(oid)));
            }
            if (pdu.size() == 0) return new HashMap<>();

            ResponseEvent event = snmp.send(pdu, target);
            if (event == null) throw new SnmpException("SNMP 无响应（超时）");
            PDU response = event.getResponse();
            if (response == null) {
                String detail = "";
                try {
                    Throwable err = event.getError();
                    if (err != null && err.getMessage() != null && !err.getMessage().isBlank())
                        detail = " | " + err.getMessage();
                } catch (Exception ignored) {}
                throw new SnmpException("SNMP 响应为空（请检查：网络/防火墙 UDP " + port + "、社区名与设备一致、设备 SNMP 已开启、可增大 snmp.firewall.timeout-ms）" + detail);
            }
            if (response.getErrorStatus() != PDU.noError)
                throw new SnmpException("SNMP 错误: " + response.getErrorStatusText());

            Map<String, String> result = new HashMap<>();
            for (VariableBinding vb : response.getVariableBindings()) {
                String oid = vb.getOid().toString();
                Variable var = vb.getVariable();
                String value = (var == null || var.isException()) ? "-" : var.toString();
                result.put(normalizeOidKey(oid), value);
            }
            return result;
        } catch (IOException e) {
            throw new SnmpException("SNMP 传输异常: " + e.getMessage(), e);
        } finally {
            if (snmp != null) try { snmp.close(); } catch (IOException ignored) {}
            if (transport != null) try { transport.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * SNMPv2c WALK：从 baseOid 子树拉取最多 maxRows 条 OID->value。
     */
    public static Map<String, String> snmpV2cWalk(
            String host,
            int port,
            String community,
            int timeoutMs,
            int retries,
            String baseOid,
            int maxRows
    ) throws SnmpException {
        if (host == null || host.isBlank()) throw new SnmpException("SNMP host 不能为空");
        if (baseOid == null || baseOid.isBlank()) return new HashMap<>();
        String comm = (community == null || community.isBlank()) ? "public" : community;

        UdpAddress targetAddress = new UdpAddress(host + "/" + port);
        TransportMapping<UdpAddress> transport = null;
        Snmp snmp = null;
        try {
            transport = new DefaultUdpTransportMapping();
            transport.listen();
            snmp = new Snmp(transport);

            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(comm));
            target.setAddress(targetAddress);
            target.setRetries(retries);
            target.setTimeout(timeoutMs);
            target.setVersion(SnmpConstants.version2c);

            TreeUtils treeUtils = new TreeUtils(snmp, new DefaultPDUFactory(PDU.GETNEXT));
            // 兼容部分设备（如部分华为 USG）返回 OID 非严格递增的情况，避免 WALK 直接中断
            treeUtils.setIgnoreLexicographicOrder(true);
            List<TreeEvent> events = treeUtils.getSubtree(target, new OID(baseOid));
            Map<String, String> out = new LinkedHashMap<>();
            if (events == null) return out;
            int limit = Math.max(1, maxRows);
            for (TreeEvent event : events) {
                if (event == null || event.isError() || event.getVariableBindings() == null) continue;
                for (VariableBinding vb : event.getVariableBindings()) {
                    if (vb == null || vb.getOid() == null) continue;
                    String oid = normalizeOidKey(vb.getOid().toString());
                    Variable var = vb.getVariable();
                    String value = (var == null || var.isException()) ? "-" : var.toString();
                    out.put(oid, value);
                    if (out.size() >= limit) return out;
                }
            }
            return out;
        } catch (IOException e) {
            throw new SnmpException("SNMP WALK 传输异常: " + e.getMessage(), e);
        } finally {
            if (snmp != null) try { snmp.close(); } catch (IOException ignored) {}
            if (transport != null) try { transport.close(); } catch (IOException ignored) {}
        }
    }

    /** 统一 OID 键格式便于查找：去掉前导点，与常量一致 */
    private static String normalizeOidKey(String oid) {
        if (oid == null) return "";
        return oid.startsWith(".") ? oid.substring(1) : oid;
    }

    /**
     * 从 SNMP GET 合并结果中按「请求的 OID」取值；遍历匹配规范化后的 key，避免设备返回 OID 与常量字符串不完全一致时取不到值。
     */
    public static String getSnmpMapValue(Map<String, String> map, String requestedOid) {
        if (map == null || requestedOid == null) return null;
        String want = normalizeOidKey(requestedOid);
        String direct = map.get(want);
        if (direct != null && !direct.isBlank() && !"-".equals(direct.trim())) return direct;
        direct = map.get(requestedOid);
        if (direct != null && !direct.isBlank() && !"-".equals(direct.trim())) return direct;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (normalizeOidKey(e.getKey()).equals(want)) {
                String v = e.getValue();
                if (!v.isBlank() && !"-".equals(v.trim())) return v;
            }
        }
        return null;
    }

    /**
     * SNMPv3 GET 指定 OID 列表，认证 SHA，加密 AES-128，安全级别 authPriv。
     *
     * @param host         设备 IP
     * @param port         端口，默认 161
     * @param timeoutMs    超时毫秒
     * @param retries      重试次数
     * @param username     用户名（与防火墙一致）
     * @param authPassword 认证密码
     * @param privPassword 加密密码
     * @param oids         要采集的 OID 列表
     * @return OID -> 字符串值；若某 OID 无值则 value 为空串或 "-"
     * @throws SnmpException 超时、认证失败、传输错误等
     */
    public static Map<String, String> snmpV3Get(
            String host,
            int port,
            int timeoutMs,
            int retries,
            String username,
            String authPassword,
            String privPassword,
            String... oids
    ) throws SnmpException {
        if (host == null || host.isBlank()) {
            throw new SnmpException("SNMP host 不能为空");
        }
        if (oids == null || oids.length == 0) {
            return new HashMap<>();
        }

        UdpAddress targetAddress = new UdpAddress(host + "/" + port);
        TransportMapping<UdpAddress> transport = null;
        Snmp snmp = null;

        try {
            transport = new DefaultUdpTransportMapping();
            transport.listen();

            snmp = new Snmp(transport);
            MPv3 mpv3 = (MPv3) snmp.getMessageProcessingModel(MPv3.ID);
            mpv3.setLocalEngineID(MPv3.createLocalEngineID());
            USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(mpv3.getLocalEngineID()), 0);
            SecurityModels.getInstance().addSecurityModel(usm);

            OctetString userOctet = new OctetString(username);
            UsmUser usmUser = new UsmUser(
                    userOctet,
                    AuthSHA.ID,
                    new OctetString(authPassword),
                    PrivAES128.ID,
                    new OctetString(privPassword)
            );
            snmp.getUSM().addUser(userOctet, usmUser);

            UserTarget target = new UserTarget();
            target.setAddress(targetAddress);
            target.setRetries(retries);
            target.setTimeout(timeoutMs);
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            target.setSecurityName(userOctet);

            PDU pdu = new ScopedPDU();
            pdu.setType(PDU.GET);
            for (String oid : oids) {
                if (oid != null && !oid.isBlank()) {
                    pdu.add(new VariableBinding(new OID(oid)));
                }
            }
            if (pdu.size() == 0) {
                return new HashMap<>();
            }

            ResponseEvent event = snmp.send(pdu, target);
            if (event == null) {
                throw new SnmpException("SNMP 无响应（超时或未收到回复）");
            }
            PDU response = event.getResponse();
            if (response == null) {
                String detail = "";
                try {
                    Throwable err = event.getError();
                    if (err != null && err.getMessage() != null && !err.getMessage().isBlank())
                        detail = " | " + err.getMessage();
                } catch (Exception ignored) {}
                throw new SnmpException("SNMP 响应为空，可能超时或认证/加密失败（请检查：1) 网络/防火墙是否放行 UDP " + port + " 2) SNMPv3 用户名/认证密码/加密密码与设备一致 3) 设备 SNMP 服务已开启 4) 可适当增大 snmp.firewall.timeout-ms）" + detail);
            }
            if (response.getErrorStatus() != PDU.noError) {
                throw new SnmpException("SNMP 错误: " + response.getErrorStatusText());
            }

            Map<String, String> result = new HashMap<>();
            for (VariableBinding vb : response.getVariableBindings()) {
                String oid = vb.getOid().toString();
                Variable var = vb.getVariable();
                String value = (var == null || var.isException()) ? "-" : var.toString();
                result.put(normalizeOidKey(oid), value);
            }
            return result;
        } catch (IOException e) {
            throw new SnmpException("SNMP 传输异常: " + e.getMessage(), e);
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException ignored) {}
            }
            if (transport != null) {
                try {
                    transport.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * SNMPv3 WALK：从 baseOid 子树拉取最多 maxRows 条 OID->value（authPriv, SHA+AES128）。
     */
    public static Map<String, String> snmpV3Walk(
            String host,
            int port,
            int timeoutMs,
            int retries,
            String username,
            String authPassword,
            String privPassword,
            String baseOid,
            int maxRows
    ) throws SnmpException {
        if (host == null || host.isBlank()) throw new SnmpException("SNMP host 不能为空");
        if (baseOid == null || baseOid.isBlank()) return new HashMap<>();
        UdpAddress targetAddress = new UdpAddress(host + "/" + port);
        TransportMapping<UdpAddress> transport = null;
        Snmp snmp = null;
        try {
            transport = new DefaultUdpTransportMapping();
            transport.listen();
            snmp = new Snmp(transport);
            MPv3 mpv3 = (MPv3) snmp.getMessageProcessingModel(MPv3.ID);
            mpv3.setLocalEngineID(MPv3.createLocalEngineID());
            USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(mpv3.getLocalEngineID()), 0);
            SecurityModels.getInstance().addSecurityModel(usm);

            OctetString userOctet = new OctetString(username);
            UsmUser usmUser = new UsmUser(
                    userOctet,
                    AuthSHA.ID,
                    new OctetString(authPassword),
                    PrivAES128.ID,
                    new OctetString(privPassword)
            );
            snmp.getUSM().addUser(userOctet, usmUser);

            UserTarget target = new UserTarget();
            target.setAddress(targetAddress);
            target.setRetries(retries);
            target.setTimeout(timeoutMs);
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            target.setSecurityName(userOctet);

            TreeUtils treeUtils = new TreeUtils(snmp, new DefaultPDUFactory(PDU.GETNEXT));
            // 兼容设备 OID 非严格递增，避免 WALK 提前失败
            treeUtils.setIgnoreLexicographicOrder(true);
            List<TreeEvent> events = treeUtils.getSubtree(target, new OID(baseOid));
            Map<String, String> out = new LinkedHashMap<>();
            if (events == null) return out;
            int limit = Math.max(1, maxRows);
            for (TreeEvent event : events) {
                if (event == null || event.isError() || event.getVariableBindings() == null) continue;
                for (VariableBinding vb : event.getVariableBindings()) {
                    if (vb == null || vb.getOid() == null) continue;
                    String oid = normalizeOidKey(vb.getOid().toString());
                    Variable var = vb.getVariable();
                    String value = (var == null || var.isException()) ? "-" : var.toString();
                    out.put(oid, value);
                    if (out.size() >= limit) return out;
                }
            }
            return out;
        } catch (IOException e) {
            throw new SnmpException("SNMP WALK 传输异常: " + e.getMessage(), e);
        } finally {
            if (snmp != null) try { snmp.close(); } catch (IOException ignored) {}
            if (transport != null) try { transport.close(); } catch (IOException ignored) {}
        }
    }

    /** SNMP 相关异常（超时、认证失败、OID 错误等） */
    public static class SnmpException extends Exception {
        public SnmpException(String message) {
            super(message);
        }
        public SnmpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
