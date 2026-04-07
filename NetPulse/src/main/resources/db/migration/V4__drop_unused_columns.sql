-- 删除库表中已不再使用的字段（实体中已移除，仅清理历史表结构）
-- 若某列或外键不存在会报错，可注释掉对应语句后重新执行

-- 操作日志：先删外键再删 user_id（已改为用 username 记录操作人）
ALTER TABLE audit_log DROP FOREIGN KEY fk_audit_user;
ALTER TABLE audit_log DROP COLUMN user_id;

-- 监控项：曾有的 oid、unit 已从实体移除
ALTER TABLE monitor_item DROP COLUMN oid;
ALTER TABLE monitor_item DROP COLUMN unit;
