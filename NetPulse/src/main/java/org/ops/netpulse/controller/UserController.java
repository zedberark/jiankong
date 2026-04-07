package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.SysUser;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.SystemConfigService;
import org.ops.netpulse.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@CrossOrigin
public class UserController {
    private static final String USER_NOTIFY_LEVELS_KEY_PREFIX = "alert.user.notify_levels.";

    private final UserService userService;
    private final AuditService auditService;
    private final SystemConfigService systemConfigService;

    @GetMapping
    public List<SysUser> list() {
        List<SysUser> users = userService.findAll();
        users.forEach(this::fillNotifyLevels);
        return users;
    }

    @GetMapping("/{id}")
    public ResponseEntity<SysUser> get(@PathVariable Long id) {
        return userService.findById(id)
                .map(u -> {
                    fillNotifyLevels(u);
                    return ResponseEntity.ok(u);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/roles")
    public List<Long> getRoles(@PathVariable Long id) {
        return userService.getRoleIdsByUserId(id);
    }

    @GetMapping("/{id}/menus")
    public List<String> getMenus(@PathVariable Long id) {
        return userService.getAllowedMenuCodes(id);
    }

    @PostMapping
    public SysUser create(@RequestBody UserDto dto) {
        SysUser u = userService.save(dto.toEntity(), dto.getRoleIds());
        saveNotifyLevels(u.getId(), dto.getAlertNotifyLevels());
        fillNotifyLevels(u);
        auditService.log("CREATE_USER", "user", u.getId(), "username=" + (u.getUsername() != null ? u.getUsername() : ""));
        return u;
    }

    @PutMapping("/{id}")
    public ResponseEntity<SysUser> update(@PathVariable Long id, @RequestBody UserDto dto) {
        if (userService.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        SysUser u = dto.toEntity();
        u.setId(id);
        SysUser saved = userService.save(u, dto.getRoleIds());
        saveNotifyLevels(id, dto.getAlertNotifyLevels());
        fillNotifyLevels(saved);
        auditService.log("UPDATE_USER", "user", id, "username=" + (saved.getUsername() != null ? saved.getUsername() : ""));
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        SysUser user = userService.findById(id).orElse(null);
        if (user != null && "admin".equalsIgnoreCase(user.getUsername())) {
            return ResponseEntity.status(403).build();
        }
        userService.deleteById(id);
        saveNotifyLevels(id, "");
        auditService.log("DELETE_USER", "user", id, "id=" + id);
        return ResponseEntity.noContent().build();
    }

    private void fillNotifyLevels(SysUser user) {
        if (user == null || user.getId() == null) return;
        user.setAlertNotifyLevels(systemConfigService.getValue(USER_NOTIFY_LEVELS_KEY_PREFIX + user.getId()).orElse(""));
    }

    private void saveNotifyLevels(Long userId, String levels) {
        if (userId == null) return;
        systemConfigService.saveByKey(USER_NOTIFY_LEVELS_KEY_PREFIX + userId, normalizeLevels(levels));
    }

    private String normalizeLevels(String levels) {
        if (levels == null || levels.isBlank()) return "";
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : levels.split("[,\\s]+")) {
            String p = part == null ? "" : part.trim().toLowerCase();
            if ("critical".equals(p) || "warning".equals(p) || "info".equals(p)) out.add(p);
        }
        return String.join(",", out);
    }

    @lombok.Data
    public static class UserDto {
        private String username;
        private String password;
        private String email;
        private Boolean enabled = true;
        private List<Long> roleIds;
        private String alertNotifyLevels;

        SysUser toEntity() {
            SysUser u = new SysUser();
            u.setUsername(username);
            u.setPassword(password != null ? password : "");
            u.setEmail(email);
            u.setEnabled(enabled != null ? enabled : true);
            return u;
        }
    }
}
