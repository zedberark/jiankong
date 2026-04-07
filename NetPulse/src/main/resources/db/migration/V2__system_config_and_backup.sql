-- 在 nebula_ops 库中执行：系统配置表、配置备份表（与现有表结构风格一致）
USE nebula_ops;

-- ----------------------------
-- 系统配置表（系统设置、API 密钥如千问/DeepSeek）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置（含API密钥等）';

-- ----------------------------
-- 配置备份记录表
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

-- 插入默认系统配置（可选）
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

-- 默认角色（用户管理用）
INSERT IGNORE INTO `role` (`code`, `name`, `description`) VALUES
('ADMIN', '管理员', '系统管理员'),
('OPERATOR', '运维', '设备与监控运维'),
('VIEWER', '只读', '仅查看');
