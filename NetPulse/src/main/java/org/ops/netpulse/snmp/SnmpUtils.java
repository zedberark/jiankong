package org.ops.netpulse.snmp;

import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.Target;

import java.io.IOException;
import java.util.*;

/**
 * SNMP 工具类：v2c（Community）与 v3（authPriv，SHA + AES-128）。
 * 支持华为防火墙/路由器、锐捷、Cisco 等设备，采集 sysName/sysDescr/ifNumber 及 CPU。
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

    /** 华为 VRP CPU 使用率（与物理编号对应，hwEntityCpuUsage） */
    public static final String OID_CPU_HUAWEI = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5";
    /**
     * 华为 AR 系列 CPU 使用率（通过 snmpwalk 验证）：
     * enterprises.2011.6.3.17.1.1.3.0.0.1 = INTEGER: 78  等
     */
    public static final String OID_CPU_HUAWEI_AR = "1.3.6.1.4.1.2011.6.3.17.1.1.3.0.0.1";
    /** Cisco cpmCPUTotalPhysicalIndex 等（CISCO-PROCESS-MIB） */
    public static final String OID_CPU_CISCO = "1.3.6.1.4.1.9.9.109.1.1.1.1.6";
    /** Cisco OLD-CISCO-CPU-MIB avgBusy5（5 分钟平均 CPU%，常见于 vIOS/IOL） */
    public static final String OID_CPU_CISCO_OLD = "1.3.6.1.4.1.9.2.1.58.0";
    /** 锐捷/通用 hrProcessorLoad */
    public static final String OID_CPU_RUIJIE = "1.3.6.1.4.1.4881.1.1.10.2.35.1.1.1.1.8";
    /** 标准 HOST-RESOURCES-MIB hrProcessorLoad（部分设备） */
    public static final String OID_CPU_HR = "1.3.6.1.2.1.25.3.3.1.2.1";

    /** 华为 VRP 内存使用率（百分比，对应 hwEntityMemUsage） */
    public static final String OID_MEM_HUAWEI = "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7";
    /** HOST-RESOURCES-MIB hrStorageUsed（索引 1 常为物理内存） */
    public static final String OID_MEM_HR_USED = "1.3.6.1.2.1.25.2.3.1.6.1";
    /** HOST-RESOURCES-MIB hrStorageSize（索引 1） */
    public static final String OID_MEM_HR_SIZE = "1.3.6.1.2.1.25.2.3.1.5.1";
    /** Cisco ciscoMemoryPoolUsed（索引 1，单位字节） */
    public static final String OID_MEM_CISCO_USED = "1.3.6.1.4.1.9.9.48.1.1.1.5.1";
    /** Cisco ciscoMemoryPoolFree（索引 1，单位字节），使用率 = used/(used+free)*100 */
    public static final String OID_MEM_CISCO_FREE = "1.3.6.1.4.1.9.9.48.1.1.1.6.1";

    private static final List<String> CPU_OIDS_ALL = List.of(
            OID_CPU_HUAWEI_AR, OID_CPU_HUAWEI, OID_CPU_CISCO, OID_CPU_CISCO_OLD, OID_CPU_RUIJIE, OID_CPU_HR
    );
    private static final List<String> MEM_OIDS_PERCENT = List.of(OID_MEM_HUAWEI);
    private static final List<String> MEM_OIDS_HR_PAIR = List.of(OID_MEM_HR_USED, OID_MEM_HR_SIZE);
    private static final List<String> MEM_OIDS_CISCO_PAIR = List.of(OID_MEM_CISCO_USED, OID_MEM_CISCO_FREE);

    private SnmpUtils() {}

    /**
     * 按厂商/类型返回待尝试的 CPU OID 列表（可传 null）。
     */
    public static List<String> getCpuOidsToTry(String vendor, String type) {
        if (vendor != null && !vendor.isBlank()) {
            String v = vendor.toLowerCase();
            if (v.contains("huawei") || v.contains("华为")) return List.of(OID_CPU_HUAWEI_AR, OID_CPU_HUAWEI, OID_CPU_HR);
            if (v.contains("h3c") || v.contains("华三")) return List.of(OID_CPU_HR);
            if (v.contains("cisco")) return List.of(OID_CPU_CISCO_OLD, OID_CPU_CISCO, OID_CPU_HR);
            if (v.contains("ruijie") || v.contains("锐捷")) return List.of(OID_CPU_RUIJIE, OID_CPU_HR);
        }
        return new ArrayList<>(CPU_OIDS_ALL);
    }

    /**
     * 返回内存采集用的 OID 列表：先尝试直接百分比（如华为），再尝试 HR used/size 对（用于计算百分比）。
     */
    public static List<String> getMemoryOidsToTry(String vendor, String type) {
        List<String> out = new ArrayList<>();
        if (vendor != null && !vendor.isBlank()) {
            String v = vendor.toLowerCase();
            if (v.contains("huawei") || v.contains("华为")) {
                out.add(OID_MEM_HUAWEI);
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
        }
        out.addAll(MEM_OIDS_PERCENT);
        out.addAll(MEM_OIDS_CISCO_PAIR);
        out.addAll(MEM_OIDS_HR_PAIR);
        return out;
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

    /** 统一 OID 键格式便于查找：去掉前导点，与常量一致 */
    private static String normalizeOidKey(String oid) {
        if (oid == null) return "";
        return oid.startsWith(".") ? oid.substring(1) : oid;
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
