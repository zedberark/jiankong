package org.ops.netpulse.config;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Role;
import org.ops.netpulse.entity.SysUser;
import org.ops.netpulse.entity.UserRole;
import org.ops.netpulse.repository.RoleRepository;
import org.ops.netpulse.repository.SysUserRepository;
import org.ops.netpulse.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 确保内置管理员 admin / admin123 存在（仅当不存在时创建） */
@Component
@Order(1)
@RequiredArgsConstructor
public class InitialAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminRunner.class);
    private static final String ADMIN_USERNAME = "admin";
    private static final String DEFAULT_WEAK_PASSWORD = "admin123";

    private final SysUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    @Value("${netpulse.init-admin-password:}")
    private String initAdminPassword;

    @Override
    public void run(ApplicationArguments args) {
        var existingAdmin = userRepository.findByUsername(ADMIN_USERNAME).orElse(null);
        if (existingAdmin != null) {
            if (Boolean.TRUE.equals(existingAdmin.getEnabled())) {
                return;
            }
            existingAdmin.setEnabled(true);
            userRepository.save(existingAdmin);
            return;
        }
        Role adminRole = roleRepository.findByCode("ADMIN").orElse(null);
        if (adminRole == null) {
            adminRole = roleRepository.save(Role.builder()
                    .code("ADMIN")
                    .name("管理员")
                    .description("系统管理员")
                    .build());
        }
        String rawPassword = (initAdminPassword == null || initAdminPassword.isBlank())
                ? DEFAULT_WEAK_PASSWORD
                : initAdminPassword;
        SysUser admin = SysUser.builder()
                .username(ADMIN_USERNAME)
                .password(passwordEncoder.encode(rawPassword))
                .enabled(true)
                .build();
        admin = userRepository.save(admin);
        userRoleRepository.save(UserRole.builder().userId(admin.getId()).roleId(adminRole.getId()).build());
        if (initAdminPassword == null || initAdminPassword.isBlank()) {
            log.warn("首次初始化管理员账号使用弱密码：admin / {}，请立即修改", rawPassword);
        }
    }
}
