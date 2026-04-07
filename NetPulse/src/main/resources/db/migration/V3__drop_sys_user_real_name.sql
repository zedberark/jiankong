-- 移除 sys_user 表未使用的 real_name 字段（用户管理已去掉「姓名」）
-- 若该列已不存在，请注释掉下一行或跳过本迁移
ALTER TABLE sys_user DROP COLUMN real_name;
