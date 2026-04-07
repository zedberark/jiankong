-- 系统巡检：一次巡检报告 + 每台设备探测结果（与定时监控独立，便于留档与对比）
CREATE TABLE IF NOT EXISTS `inspection_report` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `created_at` DATETIME NOT NULL,
  `finished_at` DATETIME NULL,
  `group_name` VARCHAR(128) NULL COMMENT '空表示全部设备',
  `total_count` INT NOT NULL DEFAULT 0,
  `ok_count` INT NOT NULL DEFAULT 0,
  `warn_count` INT NOT NULL DEFAULT 0,
  `offline_count` INT NOT NULL DEFAULT 0,
  `duration_ms` BIGINT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inspection_report_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统巡检报告';

CREATE TABLE IF NOT EXISTS `inspection_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `report_id` BIGINT NOT NULL,
  `device_id` BIGINT NOT NULL,
  `device_name` VARCHAR(255) NULL,
  `ip` VARCHAR(64) NULL,
  `device_type` VARCHAR(32) NULL,
  `rtt_ms` BIGINT NULL,
  `status` VARCHAR(16) NOT NULL COMMENT 'normal / warning / offline',
  PRIMARY KEY (`id`),
  KEY `idx_inspection_item_report` (`report_id`),
  CONSTRAINT `fk_inspection_item_report` FOREIGN KEY (`report_id`) REFERENCES `inspection_report` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统巡检明细';

-- 为管理员与运维角色默认勾选「系统巡检」菜单（与 MenuConstants.system-inspection 一致）
INSERT IGNORE INTO `role_menu` (`role_id`, `menu_code`)
SELECT r.`id`, 'system-inspection' FROM `role` r WHERE r.`code` IN ('ADMIN', 'OPERATOR', 'OPS');
