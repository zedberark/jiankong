package org.ops.netpulse.repository;

import org.ops.netpulse.entity.RoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface RoleMenuRepository extends JpaRepository<RoleMenu, Long> {

    List<RoleMenu> findByRoleId(Long roleId);

    @Modifying
    @Transactional
    void deleteByRoleId(Long roleId);
}
