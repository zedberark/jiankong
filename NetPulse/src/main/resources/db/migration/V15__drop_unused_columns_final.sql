-- 再次安全清理不需要的列与表（与 V12 逻辑一致，可重复执行）
-- 若库由 JPA 或旧脚本创建，可能仍存在下列列/表；本迁移仅当存在时删除

DELIMITER //

DROP PROCEDURE IF EXISTS drop_unused_columns_final//

CREATE PROCEDURE drop_unused_columns_final()
BEGIN
  DECLARE fk_name VARCHAR(64);

  -- sys_user：不应存在的列
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'real_name') > 0 THEN
    ALTER TABLE sys_user DROP COLUMN real_name;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'phone') > 0 THEN
    ALTER TABLE sys_user DROP COLUMN phone;
  END IF;

  -- audit_log：先删外键再删 user_id
  SET fk_name = NULL;
  SELECT CONSTRAINT_NAME INTO fk_name FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log' AND CONSTRAINT_TYPE = 'FOREIGN KEY' AND CONSTRAINT_NAME = 'fk_audit_user' LIMIT 1;
  IF fk_name IS NOT NULL AND fk_name != '' THEN
    ALTER TABLE audit_log DROP FOREIGN KEY fk_audit_user;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log' AND COLUMN_NAME = 'user_id') > 0 THEN
    ALTER TABLE audit_log DROP COLUMN user_id;
  END IF;

  -- alert_rule：先删外键再删 monitor_item_id，再删未使用列
  SET fk_name = NULL;
  SELECT CONSTRAINT_NAME INTO fk_name FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alert_rule' AND CONSTRAINT_TYPE = 'FOREIGN KEY' AND CONSTRAINT_NAME = 'fk_alert_rule_monitor' LIMIT 1;
  IF fk_name IS NOT NULL AND fk_name != '' THEN
    ALTER TABLE alert_rule DROP FOREIGN KEY fk_alert_rule_monitor;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alert_rule' AND COLUMN_NAME = 'monitor_item_id') > 0 THEN
    ALTER TABLE alert_rule DROP COLUMN monitor_item_id;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alert_rule' AND COLUMN_NAME = 'notify_channels') > 0 THEN
    ALTER TABLE alert_rule DROP COLUMN notify_channels;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alert_rule' AND COLUMN_NAME = 'duration') > 0 THEN
    ALTER TABLE alert_rule DROP COLUMN duration;
  END IF;

  -- alert_history
  IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'alert_history' AND COLUMN_NAME = 'monitor_item_id') > 0 THEN
    ALTER TABLE alert_history DROP COLUMN monitor_item_id;
  END IF;

  -- 已废弃表（先删外键再删表）
  IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_permission' AND CONSTRAINT_TYPE = 'FOREIGN KEY' AND CONSTRAINT_NAME = 'fk_rp_permission') > 0 THEN
    ALTER TABLE role_permission DROP FOREIGN KEY fk_rp_permission;
  END IF;
  DROP TABLE IF EXISTS role_permission;
  DROP TABLE IF EXISTS sys_permission;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'monitor_item') > 0 THEN
    DROP TABLE monitor_item;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'webssh_session') > 0 THEN
    DROP TABLE webssh_session;
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification_log') > 0 THEN
    DROP TABLE notification_log;
  END IF;

END//

DELIMITER ;

CALL drop_unused_columns_final();
DROP PROCEDURE IF EXISTS drop_unused_columns_final;
