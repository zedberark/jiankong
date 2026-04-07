-- 角色权限模板：每个角色可配置可见菜单，用户管理中的「可见菜单」选项将据此展示
CREATE TABLE IF NOT EXISTS `role_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` BIGINT NOT NULL COMMENT '角色ID，关联 role.id',
  `menu_code` VARCHAR(64) NOT NULL COMMENT '菜单编码，与前端菜单 code 一致，如 dashboard、devices',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_code`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限模板：角色可分配的菜单编码';
