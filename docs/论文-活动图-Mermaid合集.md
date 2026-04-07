# NetPulse 毕业设计 — 活动图（Mermaid）

活动图描述**控制流**：开始/结束、活动节点、分支与合并、循环。下列使用 Mermaid **flowchart** 语法，节点采用 `([开始/结束])`、矩形活动、`{判断}` 菱形，与 UML 活动图习惯一致。

复制到 [mermaid.live](https://mermaid.live) 可导出 **PNG/SVG** 插入 Word；图内文字可按学校要求改成「图 5-x」说明。

---

## 1 用户登录活动图

```mermaid
flowchart TD
    Start([开始]) --> A[打开登录页]
    A --> B[输入用户名与密码]
    B --> C[提交 POST /api/auth/login]
    C --> D{后端校验密码}
    D -->|失败| E[返回错误信息]
    E --> B
    D -->|成功| F[查询用户角色与 role_menu]
    F --> G[组装 allowedMenus]
    G --> H[响应 JSON：用户 + allowedMenus]
    H --> I[前端写入 localStorage]
    I --> J[跳转 from 或仪表盘]
    J --> End([结束])
```

**带泳道（用户 / 前端 / 后端）的简化版**：

```mermaid
flowchart TB
    subgraph 用户
        U1([开始]) --> U2[输入账号密码]
    end
    subgraph 前端
        F1[POST 登录请求] --> F2[保存用户信息]
        F2 --> F3[路由跳转]
    end
    subgraph 后端
        B1[校验 sys_user] --> B2{密码匹配?}
        B2 -->|否| B3[返回 401]
        B2 -->|是| B4[查询角色与菜单并返回]
    end
    U2 --> F1
    F1 --> B1
    B4 --> F2
    B3 --> F2
    F3 --> U3([结束])
```

---

## 2 设备可达性检测活动图（单台设备）

```mermaid
flowchart TD
    Start([开始]) --> A[定时任务取一台未删除设备]
    A --> B{设备类型}
    B -->|服务器| C[执行 ICMP Ping]
    B -->|网络设备| D[TCP 探测 161/22/23 等]
    C --> E{可达?}
    D --> E
    E -->|是| F[写 status、last_poll_time]
    E -->|否| G[写 offline、last_poll_time]
    F --> H[写入 InfluxDB 时序]
    G --> H
    H --> I{状态相对上次变化?}
    I -->|是| J[触发上下线告警评估]
    I -->|否| K[本设备处理结束]
    J --> K
    K --> End([结束])
```

---

## 3 告警规则评估活动图（单条规则一次判定）

```mermaid
flowchart TD
    Start([开始]) --> A[读取当前指标与设备状态]
    A --> B{规则已启用?}
    B -->|否| Z([结束])
    B -->|是| C{规则类型}
    C -->|设备上下线| D[比较设备 status 与条件]
    C -->|指标类| E[取当前指标值与阈值表达式]
    D --> F{条件满足?}
    E --> F
    F -->|否| G[不产生新告警]
    F -->|是| H[写入 alert_history firing]
    H --> I{启用自动修复?}
    I -->|是| J[执行 SSH 或本地脚本]
    I -->|否| K[可选发邮件]
    J --> K
    G --> Z
    K --> Z
```

---

## 4 Web 终端连接活动图

```mermaid
flowchart TD
    Start([开始]) --> A[用户选择设备点击连接]
    A --> B[前端建立 WebSocket]
    B --> C[后端按 deviceId 查询 device]
    C --> D[读取 ssh_user、password、ssh_port]
    D --> E{端口}
    E -->|22| F[JSch 建立 SSH 会话]
    E -->|23| G[建立 Telnet 会话]
    F --> H[进入双向转发循环]
    G --> H
    H --> I{会话仍存活?}
    I -->|是| J[WebSocket 与终端交换数据]
    J --> H
    I -->|否| K[关闭连接]
    K --> End([结束])
```

---

## 5 实时指标查询活动图（后端接口）

```mermaid
flowchart TD
    Start([开始]) --> A[GET /api/metrics/realtime]
    A --> B[从 InfluxDB 读 Linux 指标]
    A --> C[从 Redis 读网络设备缓存]
    B --> D[合并时间窗口内数据]
    C --> D
    D --> E[与 device 表匹配 IP/ID]
    E --> F[组装 stats、statsSource]
    F --> G[返回 JSON]
    G --> End([结束])
```

---

## 6 操作审计写入活动图（典型写操作）

```mermaid
flowchart TD
    Start([开始]) --> A[业务方法执行成功]
    A --> B[构造 AuditLog：action、target、detail、ip]
    B --> C[AuditService 异步或同步写库]
    C --> D[持久化 audit_log 表]
    D --> End([结束])
```

---

## 与流程图、时序图的区别（论文可写一句）

| 类型 | 侧重 |
|------|------|
| **活动图** | 控制流、分支、循环、谁先做后做 |
| **流程图**（前文） | 与活动图接近，有时不严格区分 |
| **时序图** | 对象间消息先后顺序（可另用 Mermaid `sequenceDiagram` 补充） |

---

## 导出说明

1. https://mermaid.live → 粘贴代码（含 `flowchart TD`）→ **Actions → Export PNG/SVG**  
2. 图过宽：将 `flowchart TD` 改为 `flowchart LR` 或拆成多张图  
3. **draw.io**：插入 → 高级 → **Mermaid**（部分版本支持粘贴 Mermaid）或按本图手绘 UML 活动图符号  

---

## 相关文档

- 业务流程（偏逻辑框图）：`论文-流程图-Mermaid合集.md`  
- 类图：`论文-类图-Mermaid合集.md`  
- 图示总索引：`论文图示汇总-ER图-流程图-数据库表图-架构图.md`
