-- =============================================================================
-- NetPulse「系统巡检 / 巡检报告」所需表与菜单（不懂代码也可按顺序执行）
-- 用法：用 Navicat、MySQL Workbench、命令行等连接到你的 nebula_ops 库后，整段执行一次。
-- 若某一步提示「表已存在」「字段已存在」，一般说明以前执行过，可忽略该步，继续后面。
-- =============================================================================

-- ---------- 第 1 步：建表（巡检报告 + 明细）----------
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

-- ---------- 第 2 步：增加来源字段（若报错 Duplicate column name 'source'，说明已加过，跳过即可）----------
ALTER TABLE `inspection_report`
  ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/HOURLY/DAILY_00/DAILY_18/WEEKLY_MON/WEEKLY_SUN' AFTER `duration_ms`,
  ADD COLUMN `schedule_label` VARCHAR(128) NULL COMMENT '展示用说明，如整点巡检、日报（零点）' AFTER `source`;

-- ---------- 第 3 步：菜单合并为「系统巡检」一项（可重复执行，不破坏数据）----------
INSERT IGNORE INTO `role_menu` (`role_id`, `menu_code`)
SELECT DISTINCT `role_id`, 'inspection' FROM `role_menu` WHERE `menu_code` IN ('system-inspection', 'inspection-reports');
DELETE FROM `role_menu` WHERE `menu_code` IN ('system-inspection', 'inspection-reports');

-- 给管理员/运维类角色默认勾选「系统巡检」菜单（没有这些角色可忽略报错）
INSERT IGNORE INTO `role_menu` (`role_id`, `menu_code`)
SELECT r.`id`, 'inspection' FROM `role` r WHERE r.`code` IN ('ADMIN', 'OPERATOR', 'OPS');

-- 若第 1 步后从未插入过旧菜单码，上面「从旧码合并」可能插入 0 行，属正常。
-- 若侧栏仍无「系统巡检」：用户管理 → 角色权限模板 → 勾选「系统巡检」→ 保存 → 重新登录。

-- ---------- 第 4 步：AI 巡检结论字段（若报错 Duplicate column name 'ai_summary'，说明已加过，跳过）----------
ALTER TABLE `inspection_report`
  ADD COLUMN `ai_summary` LONGTEXT NULL COMMENT 'AI 巡检分析结论' AFTER `schedule_label`;
