# 数据库补充建表说明（与现有 nebula_ops 一起使用）

在已执行过 nebula_ops 建表脚本的 MySQL 中，**额外执行**以下 SQL，用于：系统配置、API 设置（千问/DeepSeek）、配置备份、以及菜单功能所需数据。

```sql
USE nebula_ops;

-- 系统配置表（系统设置、API 密钥如千问/DeepSeek）
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

-- 配置备份记录表
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

-- 插入默认系统配置（系统设置、API、关于页、NTP 时区）
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

-- 若尚未有角色数据，可插入默认角色（用户管理中的角色下拉会用到）
INSERT IGNORE INTO `role` (`code`, `name`, `description`) VALUES
('ADMIN', '管理员', '系统管理员'),
('OPERATOR', '运维', '设备与监控运维'),
('VIEWER', '只读', '仅查看');
```

**现有表使用说明**（尽量用上你提供的表结构）：

| 表名 | 用途 |
|------|------|
| device | 设备管理、批量命令目标、配置备份导出内容 |
| monitor_item | 监控项（后续告警/采集扩展） |
| alert_rule / alert_history / notification_log | 告警规则与通知（后续扩展） |
| sys_user / role / user_role | 用户管理、角色分配 |
| audit_log | 可记录批量命令等操作（后续可写） |
| webssh_session | Web SSH 会话记录（后续可写） |
| sys_permission / role_permission | 细粒度权限（后续扩展） |

执行完上述 SQL 后重启 NetPulse 应用即可使用：系统设置、API 设置、配置备份、用户管理、批量命令、关于系统 等菜单功能。
