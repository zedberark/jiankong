package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.ConfigBackup;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.ConfigBackupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/backup")
@RequiredArgsConstructor
@CrossOrigin
public class ConfigBackupController {

    private final ConfigBackupService backupService;
    private final AuditService auditService;

    @GetMapping
    public List<ConfigBackup> list() {
        return backupService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConfigBackup> get(@PathVariable Long id) {
        return backupService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ConfigBackup create(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "backup-" + System.currentTimeMillis());
        String type = body.getOrDefault("backupType", "full");
        ConfigBackup b = backupService.createBackup(name, type, null);
        auditService.log("CREATE_BACKUP", "config_backup", b.getId(), "name=" + name + ",type=" + type);
        return b;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importBackup(@RequestBody Map<String, String> body) {
        String name = body != null ? body.getOrDefault("name", "import-" + System.currentTimeMillis()) : "import-" + System.currentTimeMillis();
        String content = body != null ? body.get("content") : null;
        try {
            ConfigBackup b = backupService.importBackup(name, content, null);
            auditService.log("IMPORT_BACKUP", "config_backup", b.getId(), "name=" + b.getName());
            return ResponseEntity.ok(b);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        try {
            Map<String, Object> result = backupService.restoreBackup(id);
            auditService.log("RESTORE_BACKUP", "config_backup", id, "id=" + id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        backupService.deleteById(id);
        auditService.log("DELETE_BACKUP", "config_backup", id, "id=" + id);
        return ResponseEntity.noContent().build();
    }
}
