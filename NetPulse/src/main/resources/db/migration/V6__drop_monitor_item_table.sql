-- 删除未使用的 monitor_item 表（无 MonitorItemRepository，业务未使用）
-- 先删外键再删列、再删表；若某外键/列/表不存在会报错，可注释对应语句后重新执行

-- alert_rule 引用 monitor_item 的外键
ALTER TABLE alert_rule DROP FOREIGN KEY fk_alert_rule_monitor;
ALTER TABLE alert_rule DROP COLUMN monitor_item_id;

-- alert_history 若报 1828 外键错误，请先执行：SHOW CREATE TABLE alert_history; 查看外键名后，在此前添加 DROP FOREIGN KEY <外键名>;
ALTER TABLE alert_history DROP COLUMN monitor_item_id;

DROP TABLE monitor_item;
