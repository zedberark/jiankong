-- ============================================================
-- NetPulse 需要新建的表与初始数据（在 nebula_ops 库中执行）
-- 执行前请确保已建好 nebula_ops 及你原有的 device、sys_user、role 等表
-- ============================================================
USE nebula_ops;

-- ----------------------------
-- 1. 系统配置表（系统设置、NTP 时区、API 密钥 千问/DeepSeek）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `system_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `config_key` VARCHAR(128) NOT NULL COMMENT '配置键',
  `config_value` TEXT DEFAULT NULL COMMENT '配置值',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置（含NTP时区、API密钥等）';

-- ----------------------------
-- 2. 配置备份记录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `config_backup` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '备份ID',
  `name` VARCHAR(100) NOT NULL COMMENT '备份名称',
  `backup_type` VARCHAR(32) NOT NULL DEFAULT 'full' COMMENT '类型：device/config/full',
  `content` LONGTEXT DEFAULT NULL COMMENT '备份内容JSON',
  `user_id` BIGINT DEFAULT NULL COMMENT '操作用户ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_backup_time` (`create_time`),
  KEY `idx_backup_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置备份记录';

-- ----------------------------
-- 3. 默认系统配置（系统名称、版本、NTP 时区默认中国上海、千问/DeepSeek API）
-- ----------------------------
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `remark`) VALUES
('system.name', 'NetPulse', '系统名称'),
('system.version', '1.0.0', '系统版本'),
('system.timezone', 'Asia/Shanghai', 'NTP/时区，默认中国上海'),
('api.qwen.enabled', '0', '千问API是否启用'),
('api.qwen.endpoint', '', '千问API端点'),
('api.qwen.key', '', '千问API Key'),
('api.deepseek.enabled', '0', 'DeepSeek API是否启用'),
('api.deepseek.endpoint', '', 'DeepSeek API端点'),
('api.deepseek.key', '', 'DeepSeek API Key');

-- ----------------------------
-- 4. 默认角色（用户管理中的角色下拉，若 role 表已有数据可跳过）
-- ----------------------------
INSERT IGNORE INTO `role` (`code`, `name`, `description`) VALUES
('ADMIN', '管理员', '系统管理员'),
('OPERATOR', '运维', '设备与监控运维'),
('VIEWER', '只读', '仅查看');

-- ----------------------------
-- 5. 内置管理员（最高权限，不可删除）用户名 admin 密码 admin123
-- ----------------------------
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `enabled`, `create_time`, `update_time`)
SELECT 'admin', 'admin123', '管理员', 1, NOW(), NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_user` WHERE `username` = 'admin' LIMIT 1);

INSERT INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `sys_user` u
CROSS JOIN `role` r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
AND NOT EXISTS (SELECT 1 FROM `user_role` ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

-- ----------------------------
-- 6. AI 运维助手会话（按用户保留）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_chat_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `title` VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '会话标题',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_session_user` (`username`),
  KEY `idx_ai_session_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI助手会话';

CREATE TABLE IF NOT EXISTS `ai_chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `session_id` BIGINT NOT NULL COMMENT '会话ID',
  `role` VARCHAR(20) NOT NULL COMMENT 'user/assistant',
  `content` TEXT NOT NULL COMMENT '内容',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_msg_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI助手消息';

-- ----------------------------
-- 7. device 表增加分组（若已有该列可跳过）
-- ----------------------------
-- 若 device 表已有 group_name 列可跳过下一行
ALTER TABLE `device` ADD COLUMN `group_name` VARCHAR(64) DEFAULT NULL COMMENT '设备分组/标签' AFTER `remark`;

-- ----------------------------
-- 8. 告警规则表（若由 JPA ddl-auto 创建可跳过）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `alert_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `device_id` BIGINT DEFAULT NULL,
  `monitor_item_id` BIGINT DEFAULT NULL,
  `metric_key` VARCHAR(50) NOT NULL,
  `rule_condition` VARCHAR(255) NOT NULL COMMENT '告警条件，如 >80 或 offline',
  `duration` INT NOT NULL DEFAULT 0,
  `severity` VARCHAR(20) NOT NULL DEFAULT 'warning' COMMENT 'info/warning/critical',
  `device_types` VARCHAR(64) DEFAULT NULL COMMENT '设备类型过滤，空=全选，逗号分隔如 server,firewall',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则';
-- 【重要】若保存告警规则时报错 "Field 'condition' doesn't have a default value"，
-- 说明表里仍是旧列名 condition，请执行根目录下 migration_alert_rule_fix.sql
-- 若表已存在且含 notify_channels 列，可执行：ALTER TABLE `alert_rule` DROP COLUMN `notify_channels`;

-- ----------------------------
-- 9. 通知渠道配置（暂未使用，存 system_config 备用）
-- ----------------------------
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `remark`) VALUES
('alert.notify.email_enabled', '0', '告警邮件通知是否启用（暂未用）'),
('alert.notify.email_to', '', '告警接收邮箱（暂未用）'),
('alert.notify.webhook_enabled', '0', '告警 Webhook 是否启用（暂未用）'),
('alert.notify.webhook_url', '', 'Webhook URL（暂未用）');
