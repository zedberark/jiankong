package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.SysUser;
import org.ops.netpulse.entity.UserRole;
import org.ops.netpulse.repository.RoleRepository;
import org.ops.netpulse.repository.SysUserRepository;
import org.ops.netpulse.repository.UserRoleRepository;
import org.ops.netpulse.config.MenuConstants;
import org.ops.netpulse.service.RoleMenuService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RoleMenuService roleMenuService;
    private final PasswordEncoder passwordEncoder;

    public List<SysUser> findAll() {
        return userRepository.findAll();
    }

    public Optional<SysUser> findById(Long id) {
        return userRepository.findById(id);
    }

    public List<Long> getRoleIdsByUserId(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
    }

    public List<String> getRoleCodesByUserId(Long userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getRoleId()).map(org.ops.netpulse.entity.Role::getCode).orElse(null))
                .filter(code -> code != null)
                .collect(Collectors.toList());
    }

    /**
     * 按角色权限模板返回可见菜单；若用户含 ADMIN 角色则始终返回全部菜单（与 MenuConstants 同步，避免新菜单漏配 role_menu 时管理员看不到）。
     */
    public List<String> getAllowedMenuCodes(Long userId) {
        List<String> roleCodes = getRoleCodesByUserId(userId);
        if (roleCodes != null && roleCodes.stream().anyMatch(c -> c != null && "ADMIN".equalsIgnoreCase(c.trim()))) {
            return new ArrayList<>(MenuConstants.ALL_MENU_CODES);
        }
        List<Long> roleIds = getRoleIdsByUserId(userId);
        return roleMenuService.getAllowedMenuCodesForRoleIds(roleIds);
    }

    @Transactional
    public SysUser save(SysUser user, List<Long> roleIds) {
        if (user.getId() != null && (user.getPassword() == null || user.getPassword().isEmpty())) {
            userRepository.findById(user.getId()).ifPresent(existing -> user.setPassword(existing.getPassword()));
        }
        if (user.getPassword() != null && !user.getPassword().isBlank() && !isBcryptHash(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        SysUser saved = userRepository.save(user);
        userRoleRepository.findByUserId(saved.getId()).forEach(ur -> userRoleRepository.delete(ur));
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                userRoleRepository.save(UserRole.builder().userId(saved.getId()).roleId(roleId).build());
            }
        }
        return saved;
    }

    private boolean isBcryptHash(String raw) {
        return raw != null && raw.startsWith("$2") && raw.length() >= 50;
    }

    @Transactional
    public void deleteById(Long id) {
        userRoleRepository.findByUserId(id).forEach(userRoleRepository::delete);
        userRepository.deleteById(id);
    }
}
