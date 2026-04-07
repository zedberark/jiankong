-- 删除未使用的权限相关表（sys_permission、role_permission）
-- 若表或外键不存在会报错，可按需注释对应语句后重新执行

-- 先删 role_permission 对 sys_permission 的外键，再删两张表
ALTER TABLE role_permission DROP FOREIGN KEY fk_rp_permission;
DROP TABLE role_permission;
DROP TABLE sys_permission;

