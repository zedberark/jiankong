-- ============================================================
-- 告警规则表列名修复：将 MySQL 保留字 condition 改为 rule_condition
-- 若保存规则时报错 "Field 'condition' doesn't have a default value"，请执行本脚本
-- 在 nebula_ops 库中执行
-- ============================================================
USE nebula_ops;

-- 若表中存在 condition 列（旧表或 JPA 曾用旧列名创建），重命名为 rule_condition
ALTER TABLE `alert_rule`
  CHANGE COLUMN `condition` `rule_condition` VARCHAR(255) NOT NULL COMMENT '告警条件，如 >80 或 offline';

-- 若报错 "Unknown column 'condition'" 说明表结构已是 rule_condition，无需执行，可忽略
