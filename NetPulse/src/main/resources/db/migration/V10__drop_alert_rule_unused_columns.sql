-- 仅删除 alert_rule 表中系统未使用的列
-- notify_channels：代码中未引用；duration：实体无该字段，历史脚本遗留
-- auto_fix_command / auto_fix_enabled / auto_fix_type 仍在使用，不删

ALTER TABLE alert_rule DROP COLUMN notify_channels;
-- 若表由旧脚本创建则存在 duration，若由 JPA 建表可能无此列，报错时可注释下一行
ALTER TABLE alert_rule DROP COLUMN duration;
