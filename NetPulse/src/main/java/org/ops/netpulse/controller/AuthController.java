package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.SysUser;
import org.ops.netpulse.repository.SysUserRepository;
import org.ops.netpulse.security.AuthTokenService;
import org.ops.netpulse.service.AuditService;
import org.ops.netpulse.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证控制器：登录、登出（前端清本地即可）。
 * 登录成功后返回用户信息、角色列表、可见菜单编码，供前端做菜单权限与路由守卫。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin
public class AuthController {

    private final SysUserRepository userRepository;
    private final UserService userService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;

    /** 登录：校验用户名密码，写审计日志，返回用户基本信息 + 角色 + 可见菜单（allowedMenus） */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body) {
        String username = body.getUsername() != null ? body.getUsername().trim() : "";
        String password = body.getPassword() != null ? body.getPassword().trim() : "";
        if (username.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请输入用户名"));
        }
        SysUser user = userRepository.findByUsernameAndEnabledTrue(username).orElse(null);
        if (user == null || password.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "用户名或密码错误"));
        }
        String stored = user.getPassword() != null ? user.getPassword().trim() : "";
        boolean ok = passwordEncoder.matches(password, stored);
        // 兼容历史明文密码：首次成功登录后自动升级为 BCrypt
        if (!ok && password.equals(stored)) {
            user.setPassword(passwordEncoder.encode(password));
            userRepository.save(user);
            ok = true;
        }
        if (!ok) return ResponseEntity.status(401).body(Map.of("success", false, "message", "用户名或密码错误"));
        auditService.log("LOGIN", "user", user.getId(), "username=" + username);
        List<String> roles = userService.getRoleCodesByUserId(user.getId());
        List<String> allowedMenus = userService.getAllowedMenuCodes(user.getId());
        String nameForToken = user.getUsername() != null ? user.getUsername() : username;
        String token = authTokenService.issueToken(nameForToken);
        // 勿用 Map.of 组装 user：若字段为 null 会 NPE，导致登录 500
        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("id", user.getId());
        userPayload.put("username", user.getUsername() != null ? user.getUsername() : "");
        userPayload.put("realName", user.getUsername() != null ? user.getUsername() : "");
        userPayload.put("token", token);
        userPayload.put("roles", roles != null ? roles : List.of());
        userPayload.put("allowedMenus", allowedMenus != null ? allowedMenus : List.of());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("user", userPayload);
        return ResponseEntity.ok(resp);
    }

    /** 登录请求体 */
    @lombok.Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
