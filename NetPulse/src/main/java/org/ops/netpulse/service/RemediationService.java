package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ops.netpulse.entity.AlertHistory;
import org.ops.netpulse.entity.AlertRule;
import org.ops.netpulse.entity.Device;
import org.ops.netpulse.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 告警触发后自动执行修复脚本（如 SSH 在设备上执行命令、或本地脚本）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RemediationService {

    private final DeviceRepository deviceRepository;
    private final BatchCommandService batchCommandService;
    @Autowired(required = false)
    @Lazy
    private AlertService alertService;

    private static final String TYPE_SSH = "ssh_command";
    private static final String TYPE_LOCAL = "local_script";

    /**
     * 异步执行修复：若规则启用了自动修复且填写了命令，则根据类型执行。
     * 不阻塞告警评估与通知。
     */
    @Async
    public void runRemediationAsync(AlertRule rule, Device device, AlertHistory history) {
        if (rule == null || !Boolean.TRUE.equals(rule.getAutoFixEnabled())) return;
        String cmd = rule.getAutoFixCommand();
        if (cmd == null || cmd.isBlank()) return;

        String type = (rule.getAutoFixType() != null && !rule.getAutoFixType().isBlank())
                ? rule.getAutoFixType().trim().toLowerCase()
                : TYPE_SSH;

        if (TYPE_SSH.equals(type)) {
            runSshRemediation(device, cmd, rule.getId(), history != null ? history.getId() : null);
        } else if (TYPE_LOCAL.equals(type)) {
            runLocalRemediation(cmd, rule.getId(), history != null ? history.getId() : null);
        } else {
            log.warn("告警自动修复：未知类型 ruleId={} autoFixType={}", rule.getId(), type);
        }
    }

    private void runSshRemediation(Device device, String command, Long ruleId, Long historyId) {
        if (device == null || device.getId() == null) {
            log.warn("告警自动修复(SSH)：设备为空 ruleId={}", ruleId);
            return;
        }
        Device fresh = deviceRepository.findById(device.getId()).orElse(null);
        if (fresh == null) {
            log.warn("告警自动修复(SSH)：设备不存在 deviceId={} ruleId={}", device.getId(), ruleId);
            return;
        }
        if (fresh.getSshUser() == null || fresh.getSshPassword() == null) {
            log.warn("告警自动修复(SSH)：设备未配置 SSH deviceId={} ruleId={}，跳过执行", fresh.getId(), ruleId);
            return;
        }
        try {
            String output = batchCommandService.runCommand(fresh, command);
            if (output != null) {
                log.info("告警自动修复(SSH) 执行成功 ruleId={} deviceId={} historyId={} 输出: {}", ruleId, fresh.getId(), historyId, output.length() > 500 ? output.substring(0, 500) + "..." : output);
                tryAutoResolve(ruleId, fresh.getId());
            } else {
                log.warn("告警自动修复(SSH) 执行无输出或失败 ruleId={} deviceId={} historyId={}", ruleId, fresh.getId(), historyId);
            }
        } catch (Exception e) {
            log.error("告警自动修复(SSH) 异常 ruleId={} deviceId={} historyId={}: {}", ruleId, fresh.getId(), historyId, e.getMessage());
        }
    }

    private void runLocalRemediation(String scriptPathOrCommand, Long ruleId, Long historyId) {
        if (scriptPathOrCommand == null || scriptPathOrCommand.isBlank()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", scriptPathOrCommand.trim());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            int exit = p.exitValue();
            if (exit == 0) {
                log.info("告警自动修复(本地脚本) 执行完成 ruleId={} historyId={} exitCode=0", ruleId, historyId);
                // 本地脚本通常作用于采集侧或外部系统，无法定位单设备时不做即时自动已处理
            } else {
                log.warn("告警自动修复(本地脚本) 退出非零 ruleId={} historyId={} exitCode={}", ruleId, historyId, exit);
            }
        } catch (Exception e) {
            log.error("告警自动修复(本地脚本) 异常 ruleId={} historyId={}: {}", ruleId, historyId, e.getMessage());
        }
    }

    private void tryAutoResolve(Long ruleId, Long deviceId) {
        if (alertService == null || ruleId == null || deviceId == null) return;
        try {
            // 稍等片刻，给设备执行命令/指标刷新留出时间
            Thread.sleep(2000L);
            alertService.tryAutoResolveAfterRemediation(ruleId, deviceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("自动修复后自动已处理检测失败 ruleId={} deviceId={}: {}", ruleId, deviceId, e.getMessage());
        }
    }
}
