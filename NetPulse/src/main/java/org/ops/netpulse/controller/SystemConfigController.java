package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.SystemConfig;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.SystemConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
@CrossOrigin
public class SystemConfigController {

    private final SystemConfigService configService;
    private final AuditService auditService;

    @GetMapping
    public List<SystemConfig> list() {
        return configService.findAll();
    }

    @PostMapping
    public SystemConfig save(@RequestBody SystemConfig config) {
        SystemConfig saved = configService.save(config);
        auditService.log("SAVE_SYSTEM_CONFIG", "system_config", saved.getId(), "key=" + (saved.getConfigKey() != null ? saved.getConfigKey() : ""));
        return saved;
    }
}
