-- 删除未使用的通知记录表（仅保留告警历史 alert_history，不再记录每条通知发送）
-- 若表不存在会报错，可注释本句后重新执行

DROP TABLE notification_log;
