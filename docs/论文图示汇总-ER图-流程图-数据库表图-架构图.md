# 论文图示汇总：ER 图、流程图、数据库表图、系统架构功能图

本文档汇总毕业设计论文所需的各类图示，均提供 **Mermaid** 源码，可在 VS Code（安装 Mermaid 插件）、Typora 或 [mermaid.live](https://mermaid.live) 中预览或导出 **PNG/SVG** 后插入 Word/PPT。

---

## 一、系统架构图（总体架构）

系统采用**前后端分离 B/S 架构**：用户层 → 表现层（前端）→ 业务层（后端）→ 数据层 / 外部系统。

```mermaid
flowchart TB
    subgraph 用户层
        U[浏览器\n管理员 / 运维人员 / 只读用户]
    end

    subgraph 表现层["表现层（前端）"]
        FE[Vue 3 + Vue Router + Vite\nAxios · xterm.js · ECharts\n登录 / 仪表盘 / 设备列表 / 拓扑 / 实时指标\nWeb 终端 / 批量命令 / 告警 / 配置备份\n操作审计 / 用户管理 / 系统设置 / AI 助手]
    end

    subgraph 业务层["业务层（后端）"]
        BE[Spring Boot\n控制器 → 服务层 → 数据访问层\n设备管理 · 状态监测 · 指标采集 · 告警\nWeb 终端 · 配置备份 · 审计 · 用户与权限\n系统配置 · AI 助手 · 批量命令\n定时任务：Ping / SNMP 采集 / 告警评估]
    end

    subgraph 数据与外部["数据层 / 外部系统"]
        MySQL[(MySQL\n设备/告警/用户\n审计/配置等)]
        Redis[(Redis\n设备指标\nSNMP 缓存)]
        Influx[(InfluxDB\n状态时序\nTelegraf 指标)]
        EXT[外部系统\n网络设备 SNMP/SSH/Telnet\n邮件服务器 · 大模型 API · Telegraf]
    end

    U --> FE
    FE -->|"HTTP (REST)\nWebSocket (终端)"| BE
    BE --> MySQL
    BE --> Redis
    BE --> Influx
    BE --> EXT
```

> **说明**：当前实现为 **REST + WebSocket**，持久化使用 MySQL / Redis / InfluxDB；异步任务由 Spring `@Scheduled` / `@Async` 等完成。

---

## 二、系统功能架构图（功能模块）

按**功能**划分：核心业务 + 支撑功能。

```mermaid
flowchart TB
    subgraph 系统["NetPulse 监控运维系统"]
        CORE[核心业务]
        SUPPORT[支撑功能]
    end

    subgraph 核心业务
        M1[设备管理\nCRUD · 分组分页 · Ping · SSH/SNMP 配置]
        M2[状态监测\n定时可达性检测 · 状态回写 · 触发告警]
        M3[指标采集\nSNMP/SSH 回退 · Telegraf/InfluxDB · Redis 缓存]
        M4[告警管理\n规则 · 阈值评估 · 历史 · 邮件通知]
        M5[Web 终端\n浏览器 SSH/Telnet · 保活 · 双向转发]
    end

    subgraph 支撑功能
        S1[配置备份\n设备/规则/系统配置 JSON 备份与恢复]
        S2[操作审计\n操作日志 · 条件查询]
        S3[用户与权限\n用户/角色/菜单 · 登录与权限校验]
        S4[系统配置\n键值对 · 时区 · API 密钥]
        S5[AI 助手\n会话 · 设备上下文 · 大模型调用]
        S6[展示与扩展\n仪表盘 · 拓扑 · 批量命令 · 网络 AI 命令]
    end

    CORE --> M1 & M2 & M3 & M4 & M5
    SUPPORT --> S1 & S2 & S3 & S4 & S5 & S6
    系统 --> CORE & SUPPORT
```

---

## 三、E-R 图（实体联系图）

核心实体及关系，含主键与关键属性。完整版（含全部属性）见 **docs/全局ER图-含属性.md**。

```mermaid
erDiagram
    sys_user {
        bigint id PK
        varchar username
        varchar password
        varchar email
        boolean enabled
    }
    role {
        bigint id PK
        varchar code
        varchar name
    }
    user_role {
        bigint user_id FK
        bigint role_id FK
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
        varchar group_name
    }
    alert_rule {
        bigint id PK
        varchar name
        bigint device_id FK
        varchar metric_key
        varchar rule_condition
    }
    alert_history {
        bigint id PK
        bigint rule_id FK
        bigint device_id FK
        varchar status
        datetime start_time
        datetime end_time
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
        varchar target_type
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
        varchar backup_type
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
        varchar role
        text content
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
    sys_user ||--o{ user_role : ""
    role ||--o{ user_role : ""
    role ||--o{ role_menu : ""
    device ||--o{ alert_rule : "device_id"
    device ||--o{ alert_history : "device_id"
    alert_rule ||--o{ alert_history : "rule_id"
    sys_user ||--o{ config_backup : "user_id"
    ai_chat_session ||--o{ ai_chat_message : "session_id"
    inspection_report ||--o{ inspection_item : "report_id CASCADE"
```

> **说明**：`inspection_item.device_id` 与 `device.id` 为**逻辑关联**（便于展示），库表未建外键；`alert_template` 无外键。

---

### 三.1 数据库表关系图（仅联系与基数，字段从简）

下图突出**表与表之间的参照关系**，适合论文「数据库概念结构」中单列一页；完整属性见 **全局ER图-含属性.md**。

```mermaid
erDiagram
    sys_user {
        bigint id PK
    }
    role {
        bigint id PK
    }
    user_role {
        bigint user_id FK
        bigint role_id FK
    }
    role_menu {
        bigint id PK
        bigint role_id FK
    }
    device {
        bigint id PK
    }
    alert_rule {
        bigint id PK
        bigint device_id FK
    }
    alert_history {
        bigint id PK
        bigint rule_id FK
        bigint device_id FK
    }
    alert_template {
        bigint id PK
    }
    system_config {
        bigint id PK
    }
    config_backup {
        bigint id PK
        bigint user_id FK
    }
    audit_log {
        bigint id PK
    }
    ai_chat_session {
        bigint id PK
    }
    ai_chat_message {
        bigint id PK
        bigint session_id FK
    }
    inspection_report {
        bigint id PK
    }
    inspection_item {
        bigint id PK
        bigint report_id FK
        bigint device_id
    }
    sys_user ||--o{ user_role : "1:N"
    role ||--o{ user_role : "1:N"
    role ||--o{ role_menu : "1:N"
    device ||--o{ alert_rule : "1:N 可空"
    device ||--o{ alert_history : "1:N"
    alert_rule ||--o{ alert_history : "1:N"
    sys_user ||--o{ config_backup : "1:N 可空"
    ai_chat_session ||--o{ ai_chat_message : "1:N"
    inspection_report ||--o{ inspection_item : "1:N CASCADE"
```

---

## 四、数据库表结构图（按业务分组的字段框图）

各表以框图形式列出，便于论文中“数据库表图”的排版。详细字段见 **docs/后端与数据库表结构对照.md**。

```mermaid
flowchart LR
    subgraph 用户与权限
        T1[sys_user\nid, username, password\nemail, enabled]
        T2[role\nid, code, name]
        T3[user_role\nuser_id, role_id]
        T4[role_menu\nid, role_id, menu_code]
    end

    subgraph 设备与告警
        T5[device\nid, name, ip, type\nstatus, group_name\nssh_*, snmp_*]
        T6[alert_rule\nid, name, device_id\nmetric_key, condition\nseverity, enabled]
        T7[alert_history\nid, rule_id, device_id\nstatus, start_time\nend_time, severity]
        T8[alert_template\nid, name, metric_key\ncondition, severity]
    end

    subgraph 配置与审计
        T9[system_config\nid, config_key\nconfig_value]
        T10[config_backup\nid, name, backup_type\ncontent, user_id]
        T11[audit_log\nid, username, action\ntarget_type, target_id\ndetail, ip, create_time]
    end

    subgraph AI
        T12[ai_chat_session\nid, username, title]
        T13[ai_chat_message\nid, session_id\nrole, content]
    end

    subgraph 系统巡检
        T14[inspection_report\nid, created_at, source\nschedule_label, group_name\ntotal/ok/warn/offline_count\nai_summary, duration_ms]
        T15[inspection_item\nid, report_id FK\ndevice_id, device_name, ip\nrtt_ms, status]
    end

    T1 -->|PK/FK| T3
    T2 -->|PK/FK| T3
    T2 -->|PK/FK| T4
    T5 -->|PK/FK| T6
    T5 -->|PK/FK| T7
    T6 -->|PK/FK| T7
    T1 -->|PK/FK| T10
    T12 -->|PK/FK| T13
    T14 -->|1:N| T15
```

> **说明**：上图为「**表结构总览**」——按业务域分组的**主要字段**示意；与 Flyway 迁移、JPA 实体一致。`inspection_item.device_id` 与 `device` 表为逻辑对应（无库级外键）。详细逐表字段见 **后端与数据库表结构对照.md**。

---

### 四.1 数据库表结构图（核心表字段明细，论文附录可用）

下列按**单表**列出常用字段，便于附录「数据字典」缩略图。

```mermaid
flowchart TB
    subgraph su["用户权限表"]
        U1["sys_user\n────────\nid PK\nusername UK\npassword, email\nenabled, create_time"]
        U2["role\n────────\nid PK\ncode UK, name"]
        U3["user_role\n────────\nuser_id + role_id PK"]
        U4["role_menu\n────────\nid PK\nrole_id FK\nmenu_code"]
    end
    subgraph da["设备与告警表"]
        D1["device\n────────\nid PK\nname, ip, type, vendor\nstatus, group_name\nssh_*, snmp_*, deleted"]
        A1["alert_rule\n────────\nid PK\ndevice_id FK 可空\nmetric_key, rule_condition\nseverity, enabled"]
        A2["alert_history\n────────\nid PK\nrule_id FK, device_id FK\nstatus, start/end_time"]
    end
    subgraph ins["系统巡检表"]
        I1["inspection_report\n────────\nid PK\ncreated_at, finished_at\nsource, schedule_label\ngroup_name, counts\nduration_ms, ai_summary"]
        I2["inspection_item\n────────\nid PK\nreport_id FK\ndevice_id, device_name\nip, rtt_ms, status"]
    end
```

---

## 五、业务流程图

### 5.1 用户登录与权限校验流程

```mermaid
flowchart TD
    A[用户打开登录页] --> B[输入用户名、密码]
    B --> C[POST /api/auth/login]
    C --> D{后端校验 sys_user}
    D -->|失败| E[返回 401 / 错误信息]
    E --> B
    D -->|成功| F[查询 user_role、role、role_menu]
    F --> G[组装 allowedMenus]
    G --> H[返回用户信息 + allowedMenus]
    H --> I[前端存入 localStorage]
    I --> J[跳转 from 或 /dashboard]
    J --> K[后续请求携带 X-User-Name]
    K --> L[路由守卫检查 menuCode]
    L -->|无权限| M[重定向 /dashboard]
    L -->|有权限| N[进入目标页面]
```

### 5.2 设备状态监测流程

```mermaid
flowchart TD
    A[定时任务触发\nMonitorService] --> B[遍历未删除设备]
    B --> C{设备类型}
    C -->|服务器| D[ICMP Ping]
    C -->|网络设备| E[TCP 端口探测\n如 161/22]
    D --> F{可达?}
    E --> F
    F -->|是| G[更新 status=normal/warning\n更新 last_poll_time]
    F -->|否| H[更新 status=offline\n更新 last_poll_time]
    G --> I[写入 InfluxDB\ndevice_status]
    H --> I
    I --> J{状态是否变化}
    J -->|是| K[触发 AlertService\n设备上下线告警]
    J -->|否| L[结束]
    K --> L
```

### 5.3 告警规则评估流程

```mermaid
flowchart TD
    A[定时或状态变更触发] --> B[获取当前指标与设备状态]
    B --> C[仅保留有效时间窗口内数据]
    C --> D[遍历已启用告警规则]
    D --> E{规则类型}
    E -->|设备上下线| F[判断设备 status]
    E -->|指标类| G[取设备当前指标值]
    F --> H{条件满足?}
    G --> H
    H -->|否| I[下一条规则]
    H -->|是| J[写入 alert_history\nstatus=firing]
    J --> K{配置自动修复?}
    K -->|是| L[执行 SSH 命令或本地脚本]
    K -->|否| M[可选发送邮件]
    L --> M
    M --> I
    I --> N[告警恢复时\n更新 history status=resolved\nend_time]
```

### 5.4 系统巡检流程（探测与可选 AI）

```mermaid
flowchart TD
    A[手动 POST /inspection/run\n或定时 InspectionScheduleService] --> B[InspectionService 并发探测]
    B --> C[写入 inspection_item\n汇总 inspection_report]
    C --> D{需要 AI 结论?}
    D -->|是| E[InspectionService.generateAiSummary]
    D -->|否| F[结束]
    E --> G[更新 ai_summary]
    G --> F
```

> 与 `论文-流程图-Mermaid合集.md` 第 **7** 节一致。

### 5.5 Web SSH 连接与数据转发流程

```mermaid
flowchart TD
    A[用户选择设备并点击连接] --> B[前端建立 WebSocket\n/api/ws/ssh?deviceId=xxx]
    B --> C[后端根据 deviceId 查 device]
    C --> D[取 ssh_user, ssh_password, ssh_port]
    D --> E{ssh_port}
    E -->|22| F[JSch 建立 SSH 连接]
    E -->|23| G[Commons Net 建立 Telnet]
    F --> H[双向转发]
    G --> H
    H --> I[WebSocket 消息 → 设备终端输入]
    H --> J[设备终端输出 → WebSocket 消息]
    I --> K[xterm.js 渲染到浏览器]
    J --> K
```

### 5.6 实时指标数据流

```mermaid
flowchart LR
    subgraph 数据源
        T[Telegraf\nLinux 主机]
        S[SNMP/SSH 采集\n网络设备]
    end

    subgraph 存储
        I[(InfluxDB\ncpu/mem 等)]
        R[(Redis\n按设备ID缓存)]
    end

    subgraph 后端
        API[GET /api/metrics/realtime]
        Match[与 device 表匹配]
        Merge[合并 stats + statsSource]
    end

    subgraph 前端
        Table[Linux 设备表\n网络设备表]
        Chart[饼图 / 趋势图]
    end

    T --> I
    S --> R
    I --> API
    R --> API
    API --> Match
    Match --> Merge
    Merge --> Table
    Merge --> Chart
```

---

## 六、后端与数据层关系简图

```mermaid
flowchart LR
    subgraph 后端["Spring Boot 后端"]
        Dev[设备管理]
        Mon[状态监测]
        Col[指标采集]
        Alt[告警管理]
        Web[Web 终端]
        Ins[系统巡检]
        Oth[备份/审计/用户/配置/AI]
    end

    MySQL[(MySQL)]
    Redis[(Redis)]
    Influx[(InfluxDB)]
    Ext[网络设备\n邮件/大模型 API]

    Dev --> MySQL
    Mon --> MySQL
    Mon --> Influx
    Col --> Redis
    Col --> Influx
    Col --> Ext
    Alt --> MySQL
    Alt --> Ext
    Web --> Ext
    Ins --> MySQL
    Ins --> Ext
    Oth --> MySQL
```

---

## 七、图示与论文章节对应

| 图示类型       | 建议论文章节 | 本文档位置           |
|----------------|--------------|----------------------|
| 系统架构图     | 第 4 章 系统设计 | 一、系统架构图；**draw.io** docs/NetPulse-系统架构图.drawio（页「1-总体架构」） |
| 系统功能架构图 | 第 4 章 系统设计 | 二、系统功能架构图；另见 论文-功能模块图.md；**draw.io** 同上文件（页「2-功能架构」） |
| 功能模块图（菜单树） | 第 3～4 章 需求/设计 | docs/论文-功能模块图.md |
| E-R 图（含属性） | 第 4 章 数据库设计 | 三、E-R 图；详见 **全局ER图-含属性.md** |
| **数据库表关系图**（外键/基数） | 第 4 章 数据库设计 | **三.1**（仅联系、字段从简） |
| **数据库表结构图**（分组字段框图） | 第 4 章 数据库设计 / 附录 | **四**（总览）、**四.1**（核心表明细） |
| 类图（分层/模块） | 第 4～5 章 设计/实现 | docs/论文-类图-Mermaid合集.md |
| 活动图         | 第 4～5 章 设计/实现 | docs/论文-活动图-Mermaid合集.md |
| 功能流程图（draw.io 含判断，六页） | 第 4～5 章 | **draw.io** docs/NetPulse-功能流程图-完整版.drawio：页「**0-系统功能全流程（含登录）**」总览 +「1-登录与权限」～「5-实时指标数据流」分模块；另见 docs/论文-流程图-Mermaid合集.md |
| 业务流程图     | 第 4 章 系统设计 / 第 5 章 实现 | 五、业务流程图       |
| 后端与数据层图 | 第 4 章 系统设计 | 六、后端与数据层关系简图 |

---

## 八、导出为图片步骤

1. **VS Code**：安装 “Mermaid” 或 “Markdown Preview Mermaid Support”，在预览中查看。
2. **在线导出**：打开 [mermaid.live](https://mermaid.live)，将上述任意代码块内容（**不含** \`\`\`mermaid 首尾标记）粘贴到左侧，右侧即渲染；点击 “Actions” → “Export” 可导出 **PNG** 或 **SVG**。
3. **插入 Word/PPT**：导出 PNG 或 SVG 后插入论文或答辩 PPT；若学校要求 Visio/draw.io 绘制，可参照本文档文字与 **docs/系统功能架构图说明.md**、**docs/全局ER图-含属性.md** 中“实体与关系说明”手绘。

---

## 九、相关文档索引

- **系统架构图（总体分层 + 功能架构，draw.io 双页）**：docs/NetPulse-系统架构图.drawio  
- **功能流程图（draw.io 六页含菱形分支）**：docs/NetPulse-功能流程图-完整版.drawio（**0-系统功能全流程（含登录）** · 1-登录与权限 · 2-设备状态监测 · 3-告警规则评估 · 4-Web终端与转发 · 5-实时指标数据流）；Mermaid 合集 docs/论文-流程图-Mermaid合集.md；简化 draw.io docs/NetPulse-流程图-毕业设计.drawio  
- **功能模块图（菜单分组 + 业务核心/支撑，Mermaid + draw.io）**：docs/论文-功能模块图.md、docs/NetPulse-功能模块图.drawio  
- **活动图（控制流/泳道，Mermaid）**：docs/论文-活动图-Mermaid合集.md  
- **类图（后端分层与模块依赖，Mermaid + draw.io 分层示意）**：docs/论文-类图-Mermaid合集.md、docs/NetPulse-类图-分层示意.drawio  
- **ER 实体关系（基数、外键、总表）**：docs/论文-ER图-实体关系一览.md  
- **论文专用：分模块 ER + 表结构框图 + 可引用正文段落**：docs/论文-数据库图示专篇-ER与表结构图.md  
- **系统功能架构图（多图）**：docs/系统功能架构图.md  
- **E-R 图完整版（含全部属性）**：docs/全局ER图-含属性.md  
- **数据库表字段对照**：docs/后端与数据库表结构对照.md  
- **系统功能架构图说明（文字框图）**：docs/系统功能架构图说明.md  
