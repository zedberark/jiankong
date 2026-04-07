package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.config.MenuConstants;
import org.ops.netpulse.entity.RoleMenu;
import org.ops.netpulse.repository.RoleMenuRepository;
import org.ops.netpulse.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 角色权限模板：按角色配置可用的菜单，用户管理中的可见菜单选项据此过滤。 */
@Service
@RequiredArgsConstructor
public class RoleMenuService {

    private final RoleMenuRepository roleMenuRepository;
    private final RoleRepository roleRepository;

    /**
     * 获取某角色的模板菜单编码列表（顺序与 MenuConstants 一致）。
     * 历史数据中的 system-inspection / inspection-reports 会归一为 inspection，与前端复选框 value 一致，否则保存后回显会全部不勾选。
     */
    public List<String> getMenuCodesByRoleId(Long roleId) {
        Set<String> set = new LinkedHashSet<>();
        for (RoleMenu rm : roleMenuRepository.findByRoleId(roleId)) {
            String key = normalizeMenuCode(rm.getMenuCode());
            if (key != null && MenuConstants.ALL_MENU_CODES.contains(key)) {
                set.add(key);
            }
        }
        return MenuConstants.ALL_MENU_CODES.stream()
                .filter(set::contains)
                .collect(Collectors.toList());
    }

    /** 合并巡检相关旧菜单码，与侧栏/路由使用的 inspection 一致。 */
    private static String normalizeMenuCode(String code) {
        if (code == null || code.isBlank()) return null;
        String t = code.trim();
        if ("system-inspection".equals(t) || "inspection-reports".equals(t)) return "inspection";
        return t;
    }

    /** 保存某角色的权限模板（覆盖原有点）。 */
    @Transactional
    public void saveRoleMenus(Long roleId, List<String> menuCodes) {
        roleMenuRepository.deleteByRoleId(roleId);
        roleMenuRepository.flush();
        if (menuCodes != null) {
            Set<String> seen = new HashSet<>();
            for (String code : menuCodes) {
                if (code == null || code.isBlank()) continue;
                String key = normalizeMenuCode(code);
                if (key == null || !MenuConstants.ALL_MENU_CODES.contains(key)) continue;
                if (seen.add(key)) {
                    roleMenuRepository.save(RoleMenu.builder().roleId(roleId).menuCode(key).build());
                }
            }
        }
    }

    /**
     * 根据角色 ID 列表得到「可选菜单」并集（用于前端展示可见菜单勾选项）。
     * 若任一角色未配置模板，则返回全部菜单（兼容旧数据）。
     */
    public List<String> getAllowedMenuCodesForRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new ArrayList<>(MenuConstants.ALL_MENU_CODES);
        }
        Set<String> union = new HashSet<>();
        boolean anyHasTemplate = false;
        for (Long roleId : roleIds) {
            List<RoleMenu> list = roleMenuRepository.findByRoleId(roleId);
            if (!list.isEmpty()) anyHasTemplate = true;
            for (RoleMenu rm : list) {
                String key = normalizeMenuCode(rm.getMenuCode());
                if (key != null) {
                    union.add(key);
                }
            }
        }
        if (!anyHasTemplate) {
            return new ArrayList<>(MenuConstants.ALL_MENU_CODES);
        }
        return MenuConstants.ALL_MENU_CODES.stream()
                .filter(union::contains)
                .collect(Collectors.toList());
    }

    /** 校验菜单编码是否均在给定角色模板允许范围内。 */
    public boolean isAllowedMenuCodesForRoleIds(List<Long> roleIds, List<String> menuCodes) {
        List<String> allowed = getAllowedMenuCodesForRoleIds(roleIds);
        if (menuCodes == null) return true;
        for (String code : menuCodes) {
            if (code != null && !code.isBlank() && !allowed.contains(code.trim())) {
                return false;
            }
        }
        return true;
    }
}
