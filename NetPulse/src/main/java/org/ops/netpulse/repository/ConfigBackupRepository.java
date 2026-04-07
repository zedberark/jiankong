package org.ops.netpulse.repository;

import org.ops.netpulse.entity.ConfigBackup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfigBackupRepository extends JpaRepository<ConfigBackup, Long> {

    List<ConfigBackup> findAllByOrderByCreateTimeDesc();
}
