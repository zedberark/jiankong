# NetPulse 毕业设计 — 后端类图（Mermaid）

与当前 `NetPulse` 工程 **Spring Boot 分层**一致：控制器（表现层）→ 服务层（业务）→ 数据访问层（持久）→ 实体。  
将代码块复制到 [mermaid.live](https://mermaid.live) 可导出 **PNG/SVG** 插入 Word。

---

## 1 总体分层与依赖关系（论文总览图）

```mermaid
classDiagram
    direction TB
    class 控制层说明 {
        <<package>>
        REST Controller 接收 HTTP
    }
    class 业务层说明 {
        <<package>>
        Service 事务与领域逻辑
    }
    class 持久层说明 {
        <<package>>
        JpaRepository 访问 MySQL
    }
    class 实体层说明 {
        <<package>>
        @Entity 与表映射
    }
    控制层说明 ..> 业务层说明 : 调用
    业务层说明 ..> 持久层说明 : 调用
    持久层说明 ..> 实体层说明 : 映射
```

**更直观的依赖链（简化类名）**：

```mermaid
flowchart TB
    subgraph 控制层["controller 包"]
        C1[DeviceController]
        C2[AlertController]
        C3[AuthController]
        C4[AiChatController]
        C5[其他 Controller...]
    end
    subgraph 业务层["service 包"]
        S1[DeviceService]
        S2[AlertService]
        S3[UserService]
        S4[AiChatService]
        S5[MonitorService 等]
    end
    subgraph 持久层["repository 包"]
        R1[DeviceRepository]
        R2[AlertRuleRepository 等]
        R3[SysUserRepository 等]
    end
    subgraph 实体["entity 包"]
        E1[Device]
        E2[AlertRule / AlertHistory]
        E3[SysUser / Role]
    end
    C1 --> S1
    C2 --> S2
    C3 --> S3
    C4 --> S4
    S1 --> R1
    S2 --> R2
    S3 --> R3
    R1 --> E1
    R2 --> E2
    R3 --> E3
```

---

## 2 设备管理模块核心类

```mermaid
classDiagram
    class DeviceController {
        -DeviceService deviceService
        -AuditService auditService
        -BatchCommandService batchCommandService
        -DeviceSnmpCollectService snmpOpt
        +list(String group) List~Device~
        +save(Device) Device
        +ping(Long id) ...
    }
    class DeviceService {
        -DeviceRepository deviceRepository
        -MonitorService monitorService
        +findAll() List~Device~
        +save(Device) Device
        +pingDevice(Long id) ...
    }
    class MonitorService {
        +轮询设备可达性
        +更新设备状态
    }
    class DeviceRepository {
        <<interface>>
        JpaRepository~Device,Long~
    }
    class Device {
        Long id
        String name
        String ip
        DeviceStatus status
        ...
    }
    DeviceController --> DeviceService : 依赖
    DeviceController ..> Device : JSON 序列化
    DeviceService --> DeviceRepository
    DeviceService --> MonitorService
    DeviceRepository ..> Device : 持久化
```

---

## 3 告警模块核心类

```mermaid
classDiagram
    class AlertController {
        -AlertRuleRepository ruleRepo
        -AlertHistoryRepository historyRepo
        -AlertTemplateRepository templateRepo
        -DeviceService deviceService
        -AuditService auditService
        -AlertEmailService emailService
        +规则 CRUD、历史分页、通知配置
    }
    class AlertService {
        -AlertRuleRepository alertRuleRepository
        -AlertHistoryRepository alertHistoryRepository
        -RemediationService remediationOpt
        -AlertEmailService emailOpt
        -DeviceStatsService statsOpt
        +evaluateRules 定时/事件
        +设备上下线与指标告警
    }
    class AlertRuleRepository {
        <<interface>>
    }
    class AlertHistoryRepository {
        <<interface>>
    }
    class AlertRule
    class AlertHistory
    AlertController --> AlertRuleRepository
    AlertController --> AlertHistoryRepository
    AlertService --> AlertRuleRepository
    AlertService --> AlertHistoryRepository
    AlertRuleRepository ..> AlertRule
    AlertHistoryRepository ..> AlertHistory
```

**说明**：`AlertController` 对部分仓储**直接注入**做查询与简单更新；复杂评估逻辑在 `AlertService` 中，由定时任务或状态变更调用。

---

## 4 认证与用户模块核心类

```mermaid
classDiagram
    class AuthController {
        -SysUserRepository userRepository
        -UserService userService
        -AuditService auditService
        -PasswordEncoder passwordEncoder
        -AuthTokenService authTokenService
        +login() 返回用户与 allowedMenus
    }
    class UserService {
        -SysUserRepository userRepository
        -UserRoleRepository userRoleRepository
        -RoleRepository roleRepository
        -RoleMenuService roleMenuService
        -PasswordEncoder passwordEncoder
        +findByUsername, save, 角色分配
    }
    class RoleMenuService {
        +getMenuCodesByRoleIds
    }
    class SysUserRepository {
        <<interface>>
    }
    class SysUser
    class Role
    AuthController --> UserService
    AuthController --> SysUserRepository
    UserService --> SysUserRepository
    UserService --> RoleMenuService
    SysUserRepository ..> SysUser
```

---

## 5 AI 运维助手模块核心类

```mermaid
classDiagram
    class AiChatController {
        -AiChatService aiChatService
        +sessions / messages / chat
    }
    class AiChatService {
        -AiChatSessionRepository sessionRepository
        -AiChatMessageRepository messageRepository
        -DeviceService deviceService
        -DeviceStatsService deviceStatsService
        -SystemConfigService configService
        +getSessions, chat, 调用 LLM
    }
    class AiChatSessionRepository {
        <<interface>>
    }
    class AiChatMessageRepository {
        <<interface>>
    }
    class AiChatSession
    class AiChatMessage
    AiChatController --> AiChatService
    AiChatService --> AiChatSessionRepository
    AiChatService --> AiChatMessageRepository
    AiChatService --> DeviceService : 设备上下文
    AiChatSessionRepository ..> AiChatSession
    AiChatMessageRepository ..> AiChatMessage
```

---

## 6 实体层主要类（与数据库表对应）

```mermaid
classDiagram
    class Device
    class AlertRule
    class AlertHistory
    class AlertTemplate
    class SysUser
    class Role
    class UserRole
    class RoleMenu
    class AuditLog
    class SystemConfig
    class ConfigBackup
    class AiChatSession
    class AiChatMessage
    class InspectionReport
    class InspectionItem
    SysUser "1" --> "*" UserRole
    Role "1" --> "*" UserRole
    Role "1" --> "*" RoleMenu
    Device "1" --> "*" AlertRule : device_id 可空
    Device "1" --> "*" AlertHistory
    AlertRule "1" --> "*" AlertHistory
    SysUser "1" --> "*" ConfigBackup
    AiChatSession "1" --> "*" AiChatMessage
    InspectionReport "1" --> "*" InspectionItem : CASCADE
```

---

## 7 系统巡检模块核心类

```mermaid
classDiagram
    class InspectionController {
        -InspectionService inspectionService
        -AuditService auditService
        +run / reports 分页 / 明细
    }
    class InspectionService {
        -InspectionReportRepository reportRepo
        -DeviceRepository deviceRepository
        -MonitorService monitorService
        +runInspection 探测并落库
        +generateAiSummary 大模型写结论
    }
    class InspectionScheduleService {
        -InspectionService inspectionService
        +@Scheduled 定时整点/日报/周报
    }
    class InspectionReportRepository {
        <<interface>>
    }
    class InspectionReport
    class InspectionItem
    InspectionController --> InspectionService
    InspectionService --> InspectionReportRepository
    InspectionReportRepository ..> InspectionReport
    InspectionReport "1" --> "*" InspectionItem
    InspectionScheduleService --> InspectionService : runInspection\ngenerateAiSummary
```

**说明**：AI 结论由 `InspectionService.generateAiSummary` 实现（内部调用 `AiChatService` 等），与 `AiChatController` 的 `/ai/inspection-summary` 复用同一套逻辑；探测与 `MonitorService` 状态判定一致。

---

## 8 跨模块依赖示例（AiChatService → DeviceService）

```mermaid
classDiagram
    AiChatService ..> DeviceService : 读取设备列表/健康摘要
    DeviceService ..> DeviceRepository
    AiChatService ..> DeviceStatsService : 指标快照
    AiChatService ..> SystemConfigService : API Key 等
```

---

## 导出与论文使用说明

1. **导出图片**：https://mermaid.live → 粘贴某一节 `classDiagram` 或 `flowchart` → Export PNG/SVG。  
2. **图过大**：拆成「2 设备」「3 告警」等多张图分别插入第 4～5 章。  
3. **与代码一致**：若后续重构类名，以 `org.ops.netpulse` 包下源码为准，再微调本文档。  
4. **draw.io**：类图更习惯用 **PlantUML / StarUML / IDEA UML**；若必须用 draw.io，可用「软件 → UML」类图形状手绘，关系参考本文档。

---

## 相关文件

- 数据库 ER：`全局ER图-含属性.md`、`NetPulse-全局ER图.drawio`  
- 流程图：`论文-流程图-Mermaid合集.md`  
- 表字段：`后端与数据库表结构对照.md`
