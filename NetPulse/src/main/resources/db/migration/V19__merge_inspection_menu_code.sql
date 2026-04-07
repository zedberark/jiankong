-- 侧栏合并：system-inspection + inspection-reports -> inspection（与 MenuConstants 一致）
INSERT IGNORE INTO `role_menu` (`role_id`, `menu_code`)
SELECT DISTINCT `role_id`, 'inspection' FROM `role_menu` WHERE `menu_code` IN ('system-inspection', 'inspection-reports');
DELETE FROM `role_menu` WHERE `menu_code` IN ('system-inspection', 'inspection-reports');
