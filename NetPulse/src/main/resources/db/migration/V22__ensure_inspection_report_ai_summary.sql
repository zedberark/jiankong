-- 若曾手动执行 DROP COLUMN ai_summary，本脚本在列不存在时补回（与 V20 一致）
-- Flyway 已执行过 V20 时仍会执行本脚本；列已存在时走 SELECT 1 空操作
SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'inspection_report'
    AND COLUMN_NAME = 'ai_summary'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `inspection_report` ADD COLUMN `ai_summary` LONGTEXT NULL COMMENT ''AI 巡检分析结论'' AFTER `schedule_label`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
