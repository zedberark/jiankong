package org.ops.netpulse.controller;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.Role;
import org.ops.netpulse.repository.RoleRepository;
import org.ops.netpulse.service.RoleMenuService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
@CrossOrigin
public class RoleController {

    /** 系统角色展示顺序：管理员、运维、访客（访客即原只读） */
    private static final List<String> ROLE_CODE_ORDER = List.of("ADMIN", "OPS", "GUEST");

    private final RoleRepository roleRepository;
    private final RoleMenuService roleMenuService;

    @GetMapping
    public List<Role> list() {
        List<Role> all = roleRepository.findAll();
        return all.stream()
                .filter(r -> !"DUTY".equals(r.getCode())) // 已移除「值班」角色，不展示
                .sorted(Comparator.comparing(r -> {
                    int i = ROLE_CODE_ORDER.indexOf(r.getCode());
                    return i >= 0 ? i : ROLE_CODE_ORDER.size();
                }))
                .collect(Collectors.toList());
    }

    /** 获取某角色的权限模板（可勾选菜单编码列表） */
    @GetMapping("/{id}/menus")
    public List<String> getRoleMenus(@PathVariable Long id) {
        return roleMenuService.getMenuCodesByRoleId(id);
    }

    /** 保存某角色的权限模板（请求体为 JSON 数组，如 ["dashboard","devices"]） */
    @PutMapping(value = "/{id}/menus", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> saveRoleMenus(@PathVariable Long id, @RequestBody List<String> menuCodes) {
        if (!roleRepository.existsById(id)) return ResponseEntity.notFound().build();
        roleMenuService.saveRoleMenus(id, menuCodes);
        return ResponseEntity.noContent().build();
    }
}
