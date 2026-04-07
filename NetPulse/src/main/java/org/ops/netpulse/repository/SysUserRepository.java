package org.ops.netpulse.repository;

import org.ops.netpulse.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    Optional<SysUser> findByUsernameAndEnabledTrue(String username);

    List<SysUser> findByIdInAndEnabledTrue(List<Long> ids);

    List<SysUser> findByEnabledTrue();
}
