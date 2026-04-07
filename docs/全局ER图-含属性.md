# NetPulse 全局 E-R 图（含属性）

本文档提供**包含属性**的全局 E-R 图，与当前后端实际使用的表一致（已剔除已删除表 monitor_item、webssh_session、notification_log、user_menu 等）。  
可在支持 Mermaid 的编辑器中预览，或使用 [mermaid.live](https://mermaid.live) 导出 PNG/SVG。

---

## 一、Mermaid 源代（含属性）

```mermaid
erDiagram
    sys_user {
        bigint id
        varchar username
        varchar password
        varchar email
        boolean enabled
        datetime create_time
        datetime update_time
    }

    role {
        bigint id
        varchar code
        varchar name
        varchar description
        datetime create_time
        datetime update_time
    }

    user_role {
        bigint user_id
        bigint role_id
    }

    role_menu {
        bigint id
        bigint role_id
        varchar menu_code
    }

    device {
        bigint id
        varchar name
        varchar ip
        varchar type
        varchar vendor
        varchar model
        varchar snmp_version
        varchar snmp_community
        text snmp_security
        int snmp_port
        int ssh_port
        varchar ssh_user
        varchar ssh_password
        varchar remark
        varchar group_name
        varchar status
        datetime last_poll_time
        datetime create_time
        datetime update_time
        boolean deleted
    }

    alert_rule {
        bigint id
        varchar name
        bigint device_id
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
        bigint id
        bigint rule_id
        bigint device_id
        varchar metric_key
        varchar trigger_value
        datetime start_time
        datetime end_time
        varchar status
        varchar severity
        text message
        datetime create_time
        datetime update_time
    }

    alert_template {
        bigint id
        varchar name
        varchar metric_key
        varchar rule_condition
        varchar severity
        varchar device_types
        datetime create_time
        datetime update_time
    }

    audit_log {
        bigint id
        varchar username
        varchar action
        varchar target_type
        bigint target_id
        text detail
        varchar ip
        datetime create_time
    }

    system_config {
        bigint id
        varchar config_key
        text config_value
        varchar remark
        datetime create_time
        datetime update_time
    }

    config_backup {
        bigint id
        varchar name
        varchar backup_type
        varchar summary
        longtext content
        bigint user_id
        datetime create_time
    }

    ai_chat_session {
        bigint id
        varchar username
        varchar title
        datetime create_time
    }

    ai_chat_message {
        bigint id
        bigint session_id
        varchar role
        text content
        datetime create_time
    }

    sys_user ||--o{ user_role : 拥有
    role ||--o{ user_role : 被分配
    role ||--o{ role_menu : 可访问菜单
    device ||--o{ alert_rule : 适用规则
    device ||--o{ alert_history : 告警设备
    alert_rule ||--o{ alert_history : 产生
    sys_user ||--o{ config_backup : 创建备份
    ai_chat_session ||--o{ ai_chat_message : 包含消息
```

---

## 二、简化版（仅主键与关键属性，便于大图排版）

若图过大可改用本简化版，只保留主键与 1～2 个关键业务属性：

```mermaid
erDiagram
    sys_user {
        bigint id PK
        varchar username
        varchar password
    }
    role {
        bigint id PK
        varchar code
        varchar name
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
        varchar status
    }
    alert_rule {
        bigint id PK
        varchar name
        bigint device_id FK
        varchar metric_key
    }
    alert_history {
        bigint id PK
        bigint rule_id FK
        bigint device_id FK
        varchar status
    }
    alert_template {
        bigint id PK
        varchar name
        varchar metric_key
    }
    audit_log {
        bigint id PK
        varchar username
        varchar action
        datetime create_time
    }
    system_config {
        bigint id PK
        varchar config_key
        text config_value
    }
    config_backup {
        bigint id PK
        varchar name
        bigint user_id FK
    }
    ai_chat_session {
        bigint id PK
        varchar username
        varchar title
    }
    ai_chat_message {
        bigint id PK
        bigint session_id FK
        text content
    }
    sys_user ||--o{ user_role : ""
    role ||--o{ user_role : ""
    role ||--o{ role_menu : ""
    device ||--o{ alert_rule : "device_id"
    device ||--o{ alert_history : "device_id"
    alert_rule ||--o{ alert_history : "rule_id"
    sys_user ||--o{ config_backup : "user_id"
    ai_chat_session ||--o{ ai_chat_message : "session_id"
```

---

## 三、实体与关系说明（供手绘或 Visio 对照）

| 实体 | 主键 | 主要属性 | 关系 |
|------|------|----------|------|
| sys_user | id | username, password, email, enabled, create_time, update_time | 与 role 多对多（经 user_role）；与 config_backup 一对多 |
| role | id | code, name, description, create_time, update_time | 与 sys_user 多对多（经 user_role）；与 role_menu 一对多 |
| user_role | user_id, role_id | 联合主键 | 关联 sys_user 与 role |
| role_menu | id | role_id, menu_code | 多对一 role |
| device | id | name, ip, type, vendor, model, ssh_*, snmp_*, status, group_name, last_poll_time, deleted, create_time, update_time | 与 alert_rule、alert_history 一对多 |
| alert_rule | id | name, device_id, device_types, metric_key, rule_condition, severity, enabled, auto_fix_*, create_time, update_time | 多对一 device；与 alert_history 一对多 |
| alert_history | id | rule_id, device_id, metric_key, trigger_value, start_time, end_time, status, severity, message, create_time, update_time | 多对一 alert_rule、device |
| alert_template | id | name, metric_key, rule_condition, severity, device_types, create_time, update_time | 独立表 |
| audit_log | id | username, action, target_type, target_id, detail, ip, create_time | 独立表（逻辑上按 username 关联用户） |
| system_config | id | config_key, config_value, remark, create_time, update_time | 独立表 |
| config_backup | id | name, backup_type, summary, content, user_id, create_time | 多对一 sys_user（user_id） |
| ai_chat_session | id | username, title, create_time | 与 ai_chat_message 一对多 |
| ai_chat_message | id | session_id, role, content, create_time | 多对一 ai_chat_session |

---

## 四、导出说明

- **VS Code**：安装 Mermaid 插件后打开本文件预览。
- **在线导出**：将上面任一代码块内容（不含 \`\`\`mermaid 标记）复制到 [mermaid.live](https://mermaid.live) 中，可导出 PNG/SVG。
- **论文插图**：若 Mermaid 渲染后实体框内文字过密，可使用「二、简化版」或按「三、实体与关系说明」在 Visio/draw.io 中手绘，并保留主键与关键属性。
