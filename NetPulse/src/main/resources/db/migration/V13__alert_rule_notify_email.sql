-- 告警规则增加「邮件通知」选项：勾选后该规则触发时发送邮件（不限于一级）
ALTER TABLE alert_rule ADD COLUMN notify_email TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在触发时发送邮件：0否 1是';
