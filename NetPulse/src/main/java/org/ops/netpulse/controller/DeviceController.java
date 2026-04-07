package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.BatchCommandService;
import org.ops.netpulse.service.DeviceService;
import org.ops.netpulse.util.OsReleaseParser;
import org.ops.netpulse.snmp.DeviceSnmpCollectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备管理接口：CRUD、分组、Ping、SNMP 单机采集、健康汇总。
 * SNMP 采集服务为可选注入，未配置时对应接口返回提示信息。
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
@CrossOrigin
public class DeviceController {

    private final DeviceService deviceService;
    private final AuditService auditService;
    private final BatchCommandService batchCommandService;

    /** SNMP 直采服务，snmp.collect.enabled=true 时存在 */
    @Autowired(required = false)
    private DeviceSnmpCollectService deviceSnmpCollectService;

    /** 设备列表，可选按分组过滤（group 为空则查全部） */
    @GetMapping
    public List<Device> list(@RequestParam(required = false) String group) {
        return deviceService.findAll(group);
    }

    /** 所有分组名称列表，供前端下拉与标签使用 */
    @GetMapping("/groups")
    public List<String> groups() {
        return deviceService.getGroupNames();
    }

    /** 按 ID 查询单台设备，未找到返回 404 */
    @GetMapping("/{id}")
    public ResponseEntity<Device> get(@PathVariable Long id) {
        return deviceService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 新增设备：IP 必填；名称为空时若为 Linux 服务器且已配 SSH，保存后尝试通过 SSH 获取主机名并回填。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Device device) {
        if (device == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "请求体不能为空"));
        }
        device.setId(null);
        if (device.getName() == null) device.setName("");
        if (device.getIp() == null || device.getIp().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请填写管理 IP"));
        }
        if (device.getType() == null) device.setType(Device.DeviceType.server);
        // 新增设备默认标记为离线，待监控任务实际探测后再更新为在线/告警
        if (device.getStatus() == null) device.setStatus(Device.DeviceStatus.offline);
        if (device.getDeleted() == null) device.setDeleted(false);
        if (device.getCreateTime() != null) device.setCreateTime(null);
        if (device.getUpdateTime() != null) device.setUpdateTime(null);
        if (device.getSnmpVersion() == null) device.setSnmpVersion(Device.SnmpVersion.v2c);
        if (device.getSshPort() == null) device.setSshPort(22);
        if (device.getSnmpPort() == null) device.setSnmpPort(161);
        try {
            Device saved = deviceService.save(device);
            // 新增时不执行 SSH 获取主机名，避免添加页面因 SSH 连接卡住；用户可在列表页对在线设备点「获取主机名」
            auditService.log("CREATE_DEVICE", "device", saved.getId(), "name=" + (saved.getName() != null ? saved.getName() : ""));
            return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(saved);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "保存失败";
            if (msg.length() > 150) msg = msg.substring(0, 150) + "...";
            return ResponseEntity.badRequest().body(Map.of("message", "添加设备失败：" + msg));
        }
    }

    /** 更新设备，不存在返回 404。SSH 密码/用户名为空时不覆盖原值，避免编辑时清空导致 Web SSH 连不上。 */
    @PutMapping("/{id}")
    public ResponseEntity<Device> update(@PathVariable Long id, @RequestBody Device device) {
        var existingOpt = deviceService.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Device existing = existingOpt.get();
        device.setId(id);
        device.setDeleted(false);
        if (device.getSshPassword() == null || device.getSshPassword().isBlank()) {
            device.setSshPassword(existing.getSshPassword());
        }
        if (device.getSshUser() == null || device.getSshUser().isBlank()) {
            device.setSshUser(existing.getSshUser());
        }
        if (device.getName() == null) device.setName("");
        Device saved = deviceService.save(device);
        saved = tryFetchHostnameAndUpdate(saved, false); // 仅在线时通过 SSH 获取主机名，不对离线设备执行
        auditService.log("UPDATE_DEVICE", "device", id, "name=" + (saved.getName() != null ? saved.getName() : ""));
        return ResponseEntity.ok(saved);
    }

    /** 若为 Linux 服务器且已配 SSH 且名称为空，通过 SSH 执行 hostname 将结果回填到设备名称。仅对在线设备执行（skipOfflineCheck=false）。 */
    private Device tryFetchHostnameAndUpdate(Device device, boolean skipOfflineCheck) {
        if (device == null || device.getType() != Device.DeviceType.server) return device;
        if (device.getName() != null && !device.getName().isBlank()) return device;
        if (device.getSshUser() == null || device.getSshUser().isBlank()
                || device.getSshPassword() == null || device.getSshPassword().isBlank()) return device;
        String hostname = batchCommandService.runCommand(device, "hostname 2>/dev/null || echo ''", skipOfflineCheck);
        if (hostname == null) hostname = "";
        hostname = hostname.trim().replaceAll("\\s+", " ");
        if (hostname.isEmpty()) return device;
        device.setName(hostname);
        return deviceService.save(device);
    }

    /** 逻辑删除设备 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        deviceService.deleteById(id);
        auditService.log("DELETE_DEVICE", "device", id, "id=" + id);
        return ResponseEntity.noContent().build();
    }

    /** 对指定设备执行一次 Ping，更新设备状态与最后轮询时间，返回 RTT 与 up/down（列表会立即刷新为在线/离线） */
    @GetMapping("/{id}/ping")
    public ResponseEntity<Map<String, Object>> ping(@PathVariable Long id) {
        long rtt = deviceService.pingAndUpdateStatus(id);
        return ResponseEntity.ok(Map.of(
                "deviceId", id,
                "rttMs", rtt,
                "status", rtt >= 0 ? "up" : "down"
        ));
    }

    /** 对指定设备执行一次 SNMP 采集（直采模式），可选写入 Redis */
    @PostMapping("/{id}/collect-snmp")
    public ResponseEntity<Map<String, Object>> collectSnmp(@PathVariable Long id) {
        if (deviceService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (deviceSnmpCollectService == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "SNMP 采集未启用（snmp.collect.enabled=true 时可用）"));
        }
        boolean ok = deviceSnmpCollectService.collectOne(id);
        if (!ok) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "设备未配置 SNMP 或采集失败，请检查设备 SNMP 社区名或 v3 配置"));
        }
        return ResponseEntity.ok(Map.of("message", "采集成功", "deviceId", id));
    }

    /** GET 同一路径返回 405，避免被当作静态资源返回 "No static resource"；实际须用 POST 调用。 */
    @GetMapping("/{id}/fetch-hostname")
    public ResponseEntity<?> fetchHostnameGet(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("message", "请使用 POST 方法调用此接口", "path", "/api/devices/" + id + "/fetch-hostname"));
    }

    /** 通过 SSH 获取 Linux 主机名并更新设备名称（仅对类型为服务器且未配置名称或名称空的设备有效）。须用 POST 调用。 */
    @PostMapping("/{id}/fetch-hostname")
    public ResponseEntity<?> fetchHostname(@PathVariable Long id) {
        var opt = deviceService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Device device = opt.get();
        if (device.getType() == null || device.getType() != Device.DeviceType.server) {
            return ResponseEntity.badRequest().body(Map.of("message", "仅支持类型为「服务器」的 Linux 设备"));
        }
        if (device.getSshUser() == null || device.getSshUser().isBlank()
                || device.getSshPassword() == null || device.getSshPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "请先配置该设备的 SSH 用户名与密码"));
        }
        if (device.getStatus() == Device.DeviceStatus.offline) {
            return ResponseEntity.badRequest().body(Map.of("message", "设备当前离线，无法通过 SSH 获取主机名，请先确认设备可达"));
        }
        // 一次 SSH 同时取主机名和 os-release 关键行，避免两次连接导致卡顿
        String combinedCmd = "hostname 2>/dev/null || echo ''; printf '\\n---OSRELEASE---\\n'; grep -E \"^(NAME|ID|ID_LIKE|VERSION)\" /etc/os-release 2>/dev/null || true";
        String combined = batchCommandService.runCommand(device, combinedCmd, false);
        if (combined == null) combined = "";
        String hostname;
        String osRelease = null;
        int sep = combined.indexOf("---OSRELEASE---");
        if (sep >= 0) {
            hostname = combined.substring(0, sep).trim().replaceAll("\\s+", " ");
            if (sep + 15 < combined.length()) osRelease = combined.substring(sep + 15).trim();
        } else {
            hostname = combined.trim().replaceAll("\\s+", " ");
        }
        if (hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "无法获取主机名（SSH 执行 hostname 无输出或失败）"));
        }
        device.setName(hostname);
        if ((device.getVendor() == null || device.getVendor().isBlank()) && osRelease != null && !osRelease.isBlank()) {
            String vendor = OsReleaseParser.parseVendor(osRelease);
            if (vendor != null && !vendor.isBlank()) {
                device.setVendor(vendor);
                auditService.log("UPDATE_DEVICE", "device", id, "vendor=fetched-via-ssh:" + vendor);
            }
        }
        Device saved = deviceService.save(device);
        auditService.log("UPDATE_DEVICE", "device", id, "name=fetched-via-ssh:" + hostname);
        return ResponseEntity.ok(Map.of("message", "已从主机获取并更新设备名称为：" + hostname, "device", saved));
    }

    /** 健康状态汇总：按 normal/warning/critical/offline 统计设备数量，供仪表盘与导出使用 */
    @GetMapping("/health")
    public Map<String, Long> health() {
        return deviceService.healthSummary().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
    }
}
