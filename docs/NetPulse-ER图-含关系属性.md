# NetPulse 全局 E-R 图（含关系与属性）

本文档提供可直接用于论文的 E-R 图源，包含：
- 实体属性（主键/关键字段）
- 实体关系（基数）
- 关系属性说明（通过中间表或关系字段体现）

---

## 1) Mermaid ER 图（全局实体 + 属性 + 关系）

```mermaid
erDiagram
    sys_user {
        bigint id PK
        varchar username
        varchar password
        varchar email
        boolean enabled
        datetime create_time
        datetime update_time
    }

    role {
        bigint id PK
        varchar code
        varchar name
        varchar description
        datetime create_time
        datetime update_time
    }

    user_role {
        bigint user_id PK_FK
        bigint role_id PK_FK
    }

    role_menu {
        bigint id PK
        bigint role_id FK
        varchar menu_code
    }

    device {
        bigint id PK
        varchar name
        varchar ip
        varchar type
        varchar vendor
        varchar model
        varchar snmp_version
        int snmp_port
        int ssh_port
        varchar ssh_user
        varchar group_name
        varchar status
        datetime last_poll_time
        datetime create_time
        datetime update_time
        boolean deleted
    }

    alert_rule {
        bigint id PK
        bigint device_id FK
        varchar name
        varchar device_types
        varchar metric_key
        varchar rule_condition
        varchar severity
        boolean enabled
        boolean notify_email
        boolean auto_fix_enabled
        varchar auto_fix_type
        text auto_fix_command
        datetime create_time
        datetime update_time
    }

    alert_history {
        bigint id PK
        bigint rule_id FK
        bigint device_id FK
        varchar metric_key
        varchar trigger_value
        varchar status
        varchar severity
        datetime start_time
        datetime end_time
        text message
        datetime create_time
        datetime update_time
    }

    alert_template {
        bigint id PK
        varchar name
        varchar metric_key
        varchar rule_condition
        varchar severity
        varchar device_types
        datetime create_time
        datetime update_time
    }

    inspection_report {
        bigint id PK
        datetime created_at
        datetime finished_at
        varchar source
        varchar schedule_label
        varchar group_name
        int total_count
        int ok_count
        int warn_count
        int offline_count
        bigint duration_ms
        longtext ai_summary
    }

    inspection_item {
        bigint id PK
        bigint report_id FK
        bigint device_id
        varchar device_name
        varchar ip
        varchar device_type
        bigint rtt_ms
        varchar status
    }

    ai_chat_session {
        bigint id PK
        varchar username
        varchar title
        text system_context
        datetime create_time
    }

    ai_chat_message {
        bigint id PK
        bigint session_id FK
        varchar role
        text content
        datetime create_time
    }

    system_config {
        bigint id PK
        varchar config_key
        text config_value
        varchar remark
        datetime create_time
        datetime update_time
    }

    config_backup {
        bigint id PK
        varchar name
        varchar backup_type
        varchar summary
        longtext content
        bigint user_id FK
        datetime create_time
    }

    audit_log {
        bigint id PK
        varchar username
        varchar action
        varchar target_type
        bigint target_id
        text detail
        varchar ip
        datetime create_time
    }

    %% 关系（基数）
    sys_user ||--o{ user_role : "拥有角色"
    role ||--o{ user_role : "被分配给用户"
    role ||--o{ role_menu : "配置菜单权限"

    device ||--o{ alert_rule : "适用规则"
    alert_rule ||--o{ alert_history : "产生历史"
    device ||--o{ alert_history : "告警对象"

    inspection_report ||--o{ inspection_item : "报告明细"
    ai_chat_session ||--o{ ai_chat_message : "会话消息"

    sys_user ||--o{ config_backup : "创建备份"
```

---

## 2) 关系属性说明（论文可直接引用）

> 说明：本系统多数“关系属性”通过中间表字段或历史表字段承载，而非单独 Relationship 表。

| 关系 | 基数 | 关系属性承载位置 | 关系属性（示例） |
|------|------|------------------|------------------|
| 用户 — 角色 | M:N | `user_role` | `user_id`, `role_id`（联合主键） |
| 角色 — 菜单 | 1:N | `role_menu` | `role_id`, `menu_code` |
| 设备 — 告警规则 | 1:N（规则侧可按设备/类型） | `alert_rule` | `device_id`, `device_types`, `metric_key`, `rule_condition` |
| 告警规则 — 告警历史 | 1:N | `alert_history` | `rule_id`, `trigger_value`, `status`, `start_time`, `end_time` |
| 设备 — 告警历史 | 1:N | `alert_history` | `device_id`, `severity`, `message` |
| 巡检报告 — 巡检明细 | 1:N | `inspection_item` | `report_id`, `device_id`, `rtt_ms`, `status` |
| AI会话 — AI消息 | 1:N | `ai_chat_message` | `session_id`, `role`, `content`, `create_time` |
| 用户 — 配置备份 | 1:N | `config_backup` | `user_id`, `backup_type`, `summary`, `create_time` |

---

## 3) 全局实体覆盖范围（用于答辩说明）

- 认证授权域：`sys_user`、`role`、`user_role`、`role_menu`
- 设备监控域：`device`、`alert_rule`、`alert_history`、`alert_template`
- 巡检与 AI 域：`inspection_report`、`inspection_item`、`ai_chat_session`、`ai_chat_message`
- 系统治理域：`system_config`、`config_backup`、`audit_log`

---

## 4) 论文插图建议

- 若正文版面有限，建议保留上方 Mermaid 图并将“关系属性说明表”作为图后文字说明。  
- 若答辩或附录需要详细展示，可同时引用：
  - `docs/全局ER图-含属性.md`（全量属性版）
  - `docs/论文-数据库图示专篇-ER与表结构图.md`（数据库专篇）

