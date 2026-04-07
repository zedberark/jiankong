-- 巡检报告来源：手动 / 定时（整点、日报、周报）
ALTER TABLE `inspection_report`
  ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/HOURLY/DAILY_00/DAILY_18/WEEKLY_MON/WEEKLY_SUN' AFTER `duration_ms`,
  ADD COLUMN `schedule_label` VARCHAR(128) NULL COMMENT '展示用说明，如整点巡检、日报（零点）' AFTER `source`;

INSERT IGNORE INTO `role_menu` (`role_id`, `menu_code`)
SELECT r.`id`, 'inspection-reports' FROM `role` r WHERE r.`code` IN ('ADMIN', 'OPERATOR', 'OPS');
