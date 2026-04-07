-- ============================================================
-- 删除不需要的字段（在 nebula_ops 库中执行）
-- 执行前请备份。若某列已不存在，对应语句会报错，可忽略。
-- ============================================================
USE nebula_ops;

-- 1. 用户表：手机号字段已不在系统中使用
ALTER TABLE `sys_user` DROP COLUMN `phone`;

-- 2. 告警规则表：若存在旧版 notify_channels 列可删除（当前逻辑未使用）
-- ALTER TABLE `alert_rule` DROP COLUMN `notify_channels`;
