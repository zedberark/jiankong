-- 删除 sys_user 表中未使用的 phone 字段
-- 若列不存在会报错，可注释本句后重新执行

ALTER TABLE sys_user DROP COLUMN phone;
