package org.ops.netpulse.config;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Role;
import org.ops.netpulse.repository.RoleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 确保系统预置角色存在（按顺序）：管理员、运维、访客（访客即原只读角色） */
@Component
@Order(2)
@RequiredArgsConstructor
public class InitialRolesRunner implements ApplicationRunner {

    private static final Map<String, String> ROLE_NAME_MAP = Map.of(
            "ADMIN", "管理员",
            "OPS", "运维",
            "GUEST", "访客"
    );

    private final RoleRepository roleRepository;

    @Override
    public void run(ApplicationArguments args) {
        // 历史数据迁移：原「只读」角色统一为「访客」(GUEST)，避免重复
        roleRepository.findByName("只读").ifPresent(role -> {
            if (roleRepository.findByCode("GUEST").isEmpty()) {
                role.setCode("GUEST");
                role.setName("访客");
                role.setDescription("访客");
                roleRepository.save(role);
            }
        });

        for (Map.Entry<String, String> e : ROLE_NAME_MAP.entrySet()) {
            String code = e.getKey();
            String name = e.getValue();
            var byCode = roleRepository.findByCode(code);
            if (byCode.isPresent()) {
                continue; // 已存在该 code，无需处理
            }
            var byName = roleRepository.findByName(name);
            if (byName.isPresent()) {
                // 已有同名角色（可能 code 不同或历史数据），仅补全 code，避免重复插入触发 uk_role_name
                Role r = byName.get();
                if (r.getCode() == null || !r.getCode().equals(code)) {
                    r.setCode(code);
                    r.setDescription(name);
                    roleRepository.save(r);
                }
                continue;
            }
            roleRepository.save(Role.builder()
                    .code(code)
                    .name(name)
                    .description(name)
                    .build());
        }
    }
}
