package org.ops.netpulse.config;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Role;
import org.ops.netpulse.entity.RoleMenu;
import org.ops.netpulse.repository.RoleMenuRepository;
import org.ops.netpulse.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 若 role_menu 为空，为管理员角色初始化全部菜单，其余角色可后续在「角色权限模板」中配置。 */
@Component
@Order(3)
@RequiredArgsConstructor
public class RoleMenuSeeder implements ApplicationRunner {

    private final RoleRepository roleRepository;
    private final RoleMenuRepository roleMenuRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (roleMenuRepository.count() > 0) return;
        roleRepository.findByCode("ADMIN").ifPresent(admin -> {
            for (String code : MenuConstants.ALL_MENU_CODES) {
                roleMenuRepository.save(RoleMenu.builder().roleId(admin.getId()).menuCode(code).build());
            }
        });
    }
}
