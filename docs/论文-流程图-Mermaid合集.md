# NetPulse 毕业设计 — 业务流程图（Mermaid 源码）

将下列代码块（**不含**首行 \`\`\`mermaid）复制到 [mermaid.live](https://mermaid.live) 可导出 **PNG / SVG**，插入 Word 或 PPT。  

**draw.io（推荐）**  
- **`NetPulse-功能流程图-完整版.drawio`**：第 **0 页为「系统功能全流程（含登录）」**——从访问前端、token 分支、登录校验、路由权限到侧栏各业务入口与后台支撑；第 1～5 页为分模块详图。含 **开始/结束椭圆、判断菱形、分支是/否、循环回边**，与下文 Mermaid 一致。  
- `NetPulse-流程图-毕业设计.drawio`：简化竖条版（无菱形），可作备份。

---

## 0 系统功能全流程（含登录与业务入口）

> 与 draw.io 页「0-系统功能全流程（含登录）」对应；论文中可放在「业务流程」节前作总览图。

```mermaid
flowchart TD
  S([开始]) --> A[用户访问前端 Vue]
  A --> T{localStorage 有有效 token?}
  T -->|否| L[打开登录页]
  L --> I[输入用户名、密码]
  I --> P[POST /api/auth/login]
  P --> C{密码校验通过?}
  C -->|否| E[401 / 提示错误]
  E --> I
  C -->|是| R[查询 user_role / role / role_menu]
  R --> U[返回 token、allowedMenus]
  U --> F[前端写入 localStorage；跳转 from 或 /dashboard]
  T -->|是| H[后续请求 Authorization: Bearer]
  F --> G{路由需 menuCode 且 allowedMenus 含该项?}
  H --> G
  G -->|否| D[重定向 /dashboard]
  G -->|是| N[进入目标业务页]
  D --> M[侧栏功能：仪表盘 / 设备管理 / 告警与巡检 / 运维 / 系统 / Web SSH 等]
  N --> M
```

---

## 1 用户登录与权限校验

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

---

## 2 设备状态监测

```mermaid
flowchart TD
    A[定时任务触发 MonitorService] --> B[遍历未删除设备]
    B --> C{设备类型}
    C -->|服务器| D[ICMP Ping]
    C -->|网络设备| E[TCP 端口探测 161/22 等]
    D --> F{可达?}
    E --> F
    F -->|是| G[更新 status 与 last_poll_time]
    F -->|否| H[更新 status=offline]
    G --> I[写入 InfluxDB device_status]
    H --> I
    I --> J{状态是否变化}
    J -->|是| K[触发 AlertService 上下线告警]
    J -->|否| L[结束]
    K --> L
```

---

## 3 告警规则评估

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
    H -->|是| J[写入 alert_history status=firing]
    J --> K{配置自动修复?}
    K -->|是| L[执行 SSH 命令或本地脚本]
    K -->|否| M[可选发送邮件]
    L --> M
    M --> I
    I --> N[告警恢复时更新 resolved 与 end_time]
```

---

## 4 Web SSH / Telnet 与数据转发

```mermaid
flowchart TD
    A[用户选择设备并点击连接] --> B[前端建立 WebSocket /api/ws/ssh]
    B --> C[后端按 deviceId 查 device]
    C --> D[读取 ssh_user、ssh_password、ssh_port]
    D --> E{ssh_port}
    E -->|22| F[JSch 建立 SSH]
    E -->|23| G[TelnetClient 建立 Telnet]
    F --> H[双向转发]
    G --> H
    H --> I[WebSocket → 终端输入]
    H --> J[终端输出 → WebSocket]
    I --> K[xterm.js 渲染]
    J --> K
```

---

## 5 实时指标数据流

```mermaid
flowchart LR
    subgraph 数据源
        T[Telegraf Linux]
        S[SNMP/SSH 采集]
    end
    subgraph 存储
        I[(InfluxDB)]
        R[(Redis)]
    end
    subgraph 后端
        API[GET /api/metrics/realtime]
        Match[与 device 匹配]
        Merge[合并 stats]
    end
    subgraph 前端
        Table[设备指标表]
        Chart[图表]
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

## 6 后端与数据层（补充）

```mermaid
flowchart LR
    subgraph 后端
        Dev[设备管理]
        Mon[状态监测]
        Col[指标采集]
        Alt[告警管理]
        Web[Web 终端]
        Oth[备份/审计/用户/AI]
    end
    MySQL[(MySQL)]
    Redis[(Redis)]
    Influx[(InfluxDB)]
    Ext[网络设备与外部服务]
    Dev --> MySQL
    Mon --> MySQL
    Mon --> Influx
    Col --> Redis
    Col --> Influx
    Col --> Ext
    Alt --> MySQL
    Alt --> Ext
    Web --> Ext
    Oth --> MySQL
```

---

## 7 系统巡检（探测汇总与可选 AI 结论）

与 `InspectionService`、`InspectionScheduleService`、`InspectionController` 及实体 `inspection_report` / `inspection_item` 一致；探测与落库在应用进程内完成。

```mermaid
flowchart TD
    A[触发：用户手动或 Spring 定时任务] --> B{入口}
    B -->|手动| C[POST /api/inspection/run]
    B -->|定时| D[InspectionScheduleService\n整点/日报/周报]
    C --> E[InspectionService\n并发 ICMP/TCP 探测]
    D --> E
    E --> F[按设备写入 inspection_item\n汇总 ok/warn/offline]
    F --> G[保存 inspection_report]
    G --> H{需要 AI 结论?\n手动勾选或定时 ai-enabled}
    H -->|是| I[InspectionService.generateAiSummary\n经 AiChatService 调大模型 API]
    H -->|否| J[结束]
    I --> K[更新 ai_summary 字段]
    K --> J
```

---

## 导出说明

1. 打开 https://mermaid.live  
2. 粘贴某一节的 flowchart 代码（从 `flowchart` 或 `flowchart TD` 那一行开始到最后）  
3. Actions → Export PNG / SVG  

若图过宽：在代码中把 `flowchart LR` 改为 `flowchart TD` 再导出。
