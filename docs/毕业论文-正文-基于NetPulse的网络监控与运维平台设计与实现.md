# 基于 Spring Boot 的网络监控与运维平台设计与实现

## 摘要

随着企业网络规模与设备数量的增长，传统人工巡检与分散式运维已难以满足可用性、可观测性与安全审计需求。本文设计并实现了一套基于 Spring Boot 的 B/S 架构网络监控与运维平台 NetPulse。系统采用 Spring Boot 3 与 Vue 3 前后端分离技术栈，使用 MySQL 存储业务数据、InfluxDB 存储时序指标、Redis 缓存网络设备实时数据，实现设备统一管理、健康状态监控、实时 CPU/内存展示、告警规则与历史记录、Web SSH 远程终端、配置备份与恢复、用户权限与操作审计、以及 AI 运维助手等核心功能。论文从需求分析、总体设计、数据库设计、功能实现等方面展开论述，并给出系统逻辑流程与数据库 E-R 关系描述，为绘图与答辩提供依据。实践表明，该平台能够有效支撑中小规模网络环境的集中监控与运维管理。

**关键词**：网络监控；运维平台；Spring Boot；Vue；MySQL；InfluxDB；Redis；告警；Web SSH

---

## 第一章 绪论

### 1.1 研究背景与意义

当前，企业数据中心与园区网络中服务器、交换机、路由器、防火墙等设备数量众多，运维人员需要实时掌握设备在线状态、性能指标与告警信息，并在故障发生时快速定位与处置。传统依赖人工登录设备执行命令、查看脚本输出的方式效率低、可追溯性差，且难以在设备数量增长时保持一致性。因此，构建一套集中式的网络监控与运维平台，实现设备发现、状态采集、指标可视化、告警管理与远程运维的自动化，具有重要的实际应用价值。

NetPulse 平台面向虚拟机 Linux 与网络设备，提供统一的设备管理、健康检查、实时指标展示、告警规则与历史、Web SSH 终端、批量命令执行、配置备份及 AI 辅助分析等功能。系统采用主流开源技术栈，便于二次开发与集成现有监控数据源（如 Telegraf、外部脚本写入 Redis），对中小型网络环境的运维自动化具有较好的参考意义。

### 1.2 国内外研究现状

在网络监控与可观测性领域，国外已有 Zabbix、Prometheus、Grafana、Nagios 等成熟方案。Zabbix 支持多种采集方式与告警渠道，部署与定制相对复杂；Prometheus 以拉模型与时序库见长，常与 Grafana 配合做可视化。国内不少企业基于上述系统进行二次开发或自研监控平台，结合运维流程与权限体系。在远程运维方面，Web SSH、堡垒机等产品已广泛应用，将浏览器与设备 SSH 打通，并记录操作审计。本系统在设计上借鉴了“设备—告警规则—历史记录”的数据模型，并针对 Linux 主机（Telegraf + InfluxDB）与网络设备（Redis 按 IP 缓存）双数据源做了统一展示与权限集成。

### 1.3 论文主要工作与结构安排

本文主要工作包括：（1）对网络监控与运维业务进行需求分析，明确功能模块与非功能需求；（2）进行系统总体设计，给出架构与模块划分，并以文字形式描述系统逻辑流程，便于绘制流程图；（3）完成数据库设计，给出实体列表、关系说明及主要表结构，便于绘制 E-R 图；（4）按功能模块阐述关键实现，包括设备管理、实时指标、告警、Web SSH、配置备份、AI 助手等；（5）对系统测试与部署进行简要说明，并总结不足与展望。

全文共分七章：第一章绪论；第二章需求分析；第三章系统总体设计；第四章数据库设计；第五章功能详细设计与实现；第六章系统测试与运行；第七章总结与展望。其中，逻辑流程与数据库关系的文字描述可直接用于绘制答辩所需的流程图与 E-R 图。

---

## 第二章 需求分析

### 2.1 业务需求

系统需支撑以下核心业务场景：（1）设备统一纳管：对服务器、路由器、交换机、防火墙等设备进行登记，记录管理 IP、类型、厂商型号、SSH 凭证与分组；（2）状态与指标监控：定期或按需对设备进行可达性检查（如 Ping），更新在线/离线/告警状态，并展示 CPU、内存等实时指标；（3）告警管理：支持按设备或设备类型配置告警规则，记录告警历史，并可扩展通知渠道与自动修复；（4）远程运维：通过 Web SSH 登录设备执行命令，支持批量命令下发与操作记录；（5）配置与审计：支持配置备份与恢复、系统参数与 API 配置、用户与角色权限、操作审计日志，满足安全与可追溯要求；（6）智能辅助：可选集成大模型 API，提供 AI 运维助手，结合当前设备与监控信息回答问题。

### 2.2 功能需求

本系统功能需求按模块归纳如下。

**认证与权限**：用户通过用户名与密码登录，后端校验 sys_user 表并查询关联角色；角色包括管理员（ADMIN）、运维（OPERATOR）、只读（VIEWER）等，前端根据角色控制菜单显示（如用户管理、系统设置仅管理员可见），后续请求可携带用户标识以用于审计与权限判断。

**设备管理**：支持设备的增删改查、批量导入（CSV 或 JSON 格式）、按分组筛选与排序；提供设备健康汇总接口，返回正常、告警、严重、离线四类状态的数量；支持单设备 Ping 与连通性检测，便于快速排查网络与配置问题。设备拓扑图页面展示设备节点、按分组筛选、拖拽布局，点击设备可进入 Web SSH。

**数据采集与状态更新**：通过定时任务或手动触发对设备管理 IP 执行 Ping，根据结果更新设备状态（normal/warning/critical/offline）与最后轮询时间；可选将设备状态时序写入 InfluxDB，用于后续趋势分析或大屏展示。

**实时指标**：区分两类数据源——Linux 设备（服务器）的 CPU/内存由 Telegraf 采集并写入 InfluxDB，后端按 host 与设备名称或 IP 匹配后返回；网络设备（路由器、交换机、防火墙）的 CPU/内存由外部脚本或采集程序写入 Redis（如 key 为 device:设备名，Hash 含 ip、cpu、mem、collect_time），后端按设备管理 IP 从 Redis 读取并匹配。前端展示表格与数据来源饼图，并支持每 10 秒自动刷新。

**告警**：告警规则支持按设备或设备类型、指标键（如 cpu_usage、mem_usage、device_status）、条件表达式（如 CPU>80 或 offline）、严重程度（info/warning/critical）配置，并可启用自动修复（SSH 命令或本地脚本）。告警触发时写入告警历史表，记录规则、设备、触发值、起止时间、状态（firing/resolved）；支持告警历史分页查询与人工标记已处理。通知记录表仅支持查询列表，邮件/钉钉等实际发送未实现。

**Web SSH**：用户选择设备后，前端与后端建立 WebSocket 连接，后端根据设备表中 SSH 凭证与目标设备建立 SSH 连接，将终端输入输出在 WebSocket 与 SSH 通道间双向转发；可选将会话信息（用户、设备、起止时间、输入日志）写入 webssh_session 表用于审计。

**批量命令**：用户选择多台设备并输入一条命令，后端对每台设备通过 SSH 执行并汇总执行结果（成功/失败、标准输出与错误输出），适用于批量巡检或配置下发。

**配置备份与恢复**：将当前设备、告警规则、系统配置等序列化为 JSON 存入 config_backup 表，支持按备份记录一键恢复，降低误操作风险。

**系统配置**：通过 system_config 表存储系统名称、版本、时区及千问/DeepSeek 等大模型 API 的启用状态、端点与密钥，供前端“系统设置”页与后端 AI 服务使用。

**审计与导出**：关键操作（登录、设备变更、用户变更、告警、备份等）写入 audit_log；并提供设备健康、告警历史、审计日志等的 CSV 导出接口，便于离线分析或合规归档。

**AI 助手**：支持多会话、多轮对话，会话与消息持久化在 ai_chat_session、ai_chat_message 表中；调用 system_config 中配置的大模型 API，结合当前设备与监控上下文返回回答，辅助运维决策。

### 2.3 非功能需求

- **可用性**：核心接口具备基本异常处理与超时控制；依赖 MySQL、Redis、InfluxDB 等中间件，需在部署文档中说明配置与高可用可选方案。
- **可扩展性**：设备类型、监控指标、告警渠道、通知方式等通过表结构与配置扩展，避免硬编码。
- **数据持久化**：业务数据落 MySQL；时序指标落 InfluxDB；网络设备实时数据经 Redis 缓存，便于跨节点按 IP 读取。

---

## 第三章 系统总体设计

### 3.1 系统架构

系统采用 B/S 架构与前后端分离设计。用户通过浏览器访问前端（Vue 3 + Vite），前端通过 HTTP/WebSocket 与后端（Spring Boot 3）通信；后端对接 MySQL（nebula_ops）、InfluxDB、Redis。MySQL 存储用户、角色、设备、告警规则与历史、配置备份、审计、Web SSH 会话、AI 会话与消息等；InfluxDB 存储 Telegraf 写入的 CPU/内存等时序数据；Redis 存储网络设备按 IP 的 CPU/内存等实时数据（如 key：device:R1，Hash：ip、cpu、mem、collect_time）。整体架构清晰，便于在不同环境中部署与扩展。

### 3.1.1 为什么采用 Spring Boot

本系统后端基于 **Spring Boot** 构建，选型理由如下。

**（1）与项目需求高度匹配**  
网络监控与运维平台需要提供大量 RESTful 接口（设备、告警、用户、配置等）、定时任务（设备健康检查、告警规则评估）、长连接（Web SSH 的 WebSocket 代理）以及多数据源访问（MySQL、Redis、InfluxDB、HTTP 调用外部 API）。Spring Boot 在以上方面均有成熟方案：Spring Web 提供 MVC 与 REST、Spring Data JPA 简化 MySQL 访问、Spring Data Redis 支持 Redis 与 Pipeline、Spring Scheduling 支持定时任务、Spring WebSocket 支持 WebSocket，且通过自动配置与起步依赖可快速集成，减少样板代码与配置量，便于在有限开发周期内完成多模块功能。

**（2）内嵌容器与部署简单**  
Spring Boot 内嵌 Tomcat（或 Jetty），应用打包为单一可执行 jar 后可直接通过 `java -jar` 运行，无需单独安装与配置 Web 容器，适合在虚拟机或容器环境中部署。本系统默认端口 8082，通过配置文件或环境变量即可修改端口与数据源地址，运维成本低，与“集中监控、快速部署”的目标一致。

**（3）企业级特性与可维护性**  
Spring 生态具备完善的依赖注入、面向接口编程与分层架构支持，便于将控制器、服务、仓储分离，单元测试与集成测试更容易编写。同时，Spring Boot 与 Java 17 LTS 结合，类型安全、工具链成熟，日志、异常处理、事务管理等均有统一风格，有利于后期扩展（如增加告警通知渠道、新数据源）与团队协作。

**（4）生态与社区**  
Spring Boot 文档完善、社区活跃，与 MySQL、Redis、InfluxDB 等中间件的集成示例与最佳实践丰富，遇到问题易于排查。对于毕业设计或中小型项目而言，选用 Spring Boot 既能保证技术路线主流、可写进论文，又能在实现功能的同时积累企业级 Java 开发经验。

综上，本系统采用 Spring Boot 作为后端框架，是为了在满足功能需求（多数据源、定时任务、WebSocket、RESTful API）的前提下，提高开发效率、简化部署与运维，并保持代码结构清晰、便于扩展与答辩说明。

**其他技术选型**：前端选用 Vue 3 与 Vite，组件化开发效率高，与后端 RESTful API 对接简单。MySQL 作为关系型数据库承担业务主库，满足事务与复杂查询；InfluxDB 专有时序存储与查询能力，适合指标与状态随时间变化的场景；Redis 作为缓存与键值存储，支持 Pipeline 批量操作，适合高并发读取网络设备实时数据。上述组合在保证功能完整的同时，兼顾开发效率与运行性能。

### 3.2 功能模块划分

- **认证与权限模块**：登录、会话、角色与权限、前端路由与菜单控制。
- **设备管理模块**：设备 CRUD、批量导入、分组筛选、健康汇总、Ping 与连通性检测、设备拓扑图展示。
- **数据采集与指标模块**：定时/手动状态更新、InfluxDB 查询（Linux 设备）、Redis 查询（网络设备）、实时指标聚合与来源标识。
- **告警模块**：告警规则 CRUD、告警模板与应用、告警评估逻辑、告警历史查询与人工处理、自动修复（SSH/本地脚本）；通知记录仅支持查询列表。
- **Web SSH 与批量命令模块**：WebSocket 代理、SSH 连接管理、批量命令执行与结果汇总。
- **配置备份与系统配置模块**：备份创建与恢复、系统配置键值管理。
- **AI 助手模块**：会话与消息持久化、大模型 API 调用与流式或非流式返回。
- **审计与导出模块**：审计日志写入与查询、CSV 导出。

### 3.3 系统逻辑流程（供绘制流程图）

**用户登录与权限**：用户输入用户名与密码 → 提交登录请求 → 后端校验 sys_user 并查询 user_role、role → 返回用户信息与角色列表 → 前端保存并据此展示菜单与功能。

**设备管理与健康**：管理员在设备管理页进行增删改查或批量导入 → 数据写入 device 表；定时或手动触发健康检查 → 对设备 IP 执行 Ping → 更新 device 的 status、last_poll_time，可选写入 InfluxDB → 前端请求设备列表与健康汇总并展示。

**实时指标**：前端请求实时指标接口 → 后端从 InfluxDB 按 host 获取 Linux 设备 CPU/内存，从 Redis 按 IP 获取网络设备 CPU/内存 → 与 device 表匹配后合并 stats 与 statsSource → 返回前端；前端按数据来源展示表格与饼图。

**告警**：配置告警规则写入 alert_rule；评估时根据规则与设备类型获取当前指标 → 满足条件则写入 alert_history（status=firing），可选执行自动修复（SSH/本地脚本）；恢复后更新 alert_history 的 status=resolved、end_time。通知记录表当前仅支持查询，不写入。

**Web SSH**：用户选择设备发起连接 → 前端建立 WebSocket → 后端根据 device 的 SSH 凭证与设备建立 SSH → 双向转发数据，可选记录 webssh_session → 断开时更新会话结束时间。

**配置备份**：用户创建备份 → 后端汇总设备、告警规则、系统配置等 → 序列化为 JSON 写入 config_backup；恢复时解析 JSON 并写回相应表。

**AI 助手**：用户发送消息 → 后端读取或创建 ai_chat_session、加载 ai_chat_message 历史 → 调用配置的 API 获取回复 → 写入 ai_chat_message 并返回前端。

以上流程均可在绘图工具中绘制为“流程图”或“业务流程图”，节点为系统/模块/表，箭头为数据流或调用关系。

---

## 第四章 数据库设计

### 4.1 设计原则

数据库设计遵循以下原则：（1）与业务实体一致，主要表与 JPA 实体一一对应；（2）适度规范化，减少冗余，通过外键或逻辑关联表达关系；（3）便于扩展，如告警渠道通过配置表或枚举扩展；（4）保留审计与时间戳，关键表含 create_time、update_time 或 create_time 便于追溯。

### 4.2 E-R 设计与实体关系（供绘制 E-R 图）

**核心实体**：sys_user（用户）、role（角色）、device（设备）、alert_rule（告警规则）、alert_history（告警历史）、notification_log（通知记录，仅查询）、config_backup（配置备份）、system_config（系统配置）、audit_log（审计日志）、webssh_session（Web SSH 会话）、ai_chat_session（AI 会话）、ai_chat_message（AI 消息）、alert_template（告警模板）、sys_permission（权限）、user_role（用户-角色）、role_permission（角色-权限）。monitor_item（监控项）表存在、告警规则可关联，当前无独立管理界面。

**关系简述**：  
- 用户与角色：多对多，通过 user_role 关联（user_id, role_id）。  
- 角色与权限：多对多，通过 role_permission 关联（role_id, permission_id）。  
- 设备：独立实体；alert_rule、monitor_item、alert_history、webssh_session 通过 device_id 引用 device。  
- 告警规则：可关联 device（device_id）；alert_history 关联 rule_id、device_id；notification_log 关联 alert_id（alert_history.id），当前仅支持查询。monitor_item 表预留，告警规则可选关联 monitor_item_id。  
- 配置备份：通过 user_id 关联用户。  
- 审计日志：通过 user_id 关联用户（可选），并冗余 username、ip。  
- Web SSH 会话：user_id 关联用户，device_id 关联设备。  
- AI 消息：session_id 关联 ai_chat_session。  
- system_config、alert_template 为独立配置/模板表。

绘图时可将上述实体画成矩形，关系画成菱形或连线，并标注 1、n 或 M、N。

### 4.3 主要表结构说明

**device**：id（主键）、name、ip、type（router/switch/server/firewall/other）、vendor、model、ssh_port、ssh_user、ssh_password、remark、group_name、status（normal/warning/critical/offline）、last_poll_time、create_time、update_time、deleted。用于存储设备基本信息与 SSH 等凭证，支持逻辑删除。（表中另有 snmp 相关字段预留扩展，当前版本未使用。）

**sys_user**：id、username、password、real_name、email、enabled、create_time、update_time。用于登录与权限主体。

**role**：id、code、name、description、create_time、update_time。如 ADMIN、OPERATOR、VIEWER。

**user_role**：user_id、role_id，联合主键。用户与角色多对多。

**alert_rule**：id、name、device_id、device_types、metric_key、rule_condition、severity、enabled、auto_fix_enabled、auto_fix_type、auto_fix_command、create_time、update_time。定义告警条件与可选自动修复。（表中另有 monitor_item_id 预留。）

**alert_history**：id、rule_id、device_id、monitor_item_id、metric_key、trigger_value、start_time、end_time、status（firing/resolved）、severity、message、create_time、update_time。记录每次告警触发与恢复。

**monitor_item**：id、device_id、name、metric_key、oid、collector_type、data_type、unit、interval、enabled、create_time、update_time。按设备定义的监控项（表存在，当前无独立管理功能，告警规则可关联）。

**config_backup**：id、name、backup_type、summary、content（LONGTEXT）、user_id、create_time。备份内容为 JSON。

**audit_log**：id、user_id、username、action、target_type、target_id、detail、ip、create_time。记录关键操作。

**webssh_session**：id、user_id、device_id、session_id、start_time、end_time、input_log、create_time、update_time。Web SSH 会话记录。

**ai_chat_session**：id、username、title、create_time。  
**ai_chat_message**：id、session_id、role（user/assistant）、content、create_time。

**system_config**：id、config_key（唯一）、config_value、remark、create_time、update_time。键值型系统配置。

**notification_log**：id、alert_id、channel、recipient、content、status、error_msg、create_time。通知记录表，当前仅支持查询列表，实际邮件/钉钉等发送未实现。

**alert_template**：id、name、metric_key、rule_condition、severity、device_types、create_time、update_time。告警模板，便于快速创建规则。

**sys_permission**、**role_permission**：权限与角色-权限多对多关联。

### 4.4 索引与关键字段

主要索引包括：device 的 ip、group_name、deleted、status；sys_user 的 username；alert_rule 的 device_id、enabled；alert_history 的 rule_id、device_id、start_time、status；audit_log 的 user_id、action、create_time；ai_chat_message 的 session_id；config_backup 的 create_time、user_id；system_config 的 config_key（唯一）。上述设计在保证查询效率的同时，便于与 JPA 实体及现有建表脚本一致。

---

## 第五章 功能详细设计与实现

### 5.1 认证与权限

认证采用表单登录：前端提交用户名与密码至 POST /auth/login，后端在 sys_user 中查询并比对密码（当前为明文比对，生产环境建议改为摘要或加密存储），通过后查询 user_role、role 得到角色列表，并写入审计日志。登录成功返回用户信息与角色，前端将用户信息存入 localStorage，后续请求可通过请求头传递用户标识（如 X-User-Name）。前端根据角色控制菜单显示（如 ADMIN 可见用户管理、系统设置），部分敏感接口可在后端按角色或权限做二次校验。角色与权限通过 role、sys_permission、role_permission 三张表实现 RBAC 模型，便于后续扩展细粒度权限。

### 5.2 设备管理

设备管理提供设备列表的增删改查与批量导入。列表支持按分组筛选、按 IP/类型/状态排序；单条设备包含名称、IP、类型、厂商型号、SSH 端口与凭证、备注、分组、状态等。新增与编辑通过表单提交至 POST/PUT /api/devices，后端校验后写入或更新 device 表，并支持逻辑删除（deleted 标记）。批量导入接收 JSON 数组（名称、IP、类型等），逐条校验并插入。健康汇总接口 GET /api/devices/health 对 device 表按 status 分组统计，返回 normal、warning、critical、offline 数量。单设备 Ping 与连通性检测由专门接口实现，用于快速验证设备可达性。

### 5.3 数据采集与实时指标

设备状态通过定时或手动触发 Ping 更新：遍历未删除设备，对其管理 IP 执行 Ping，根据结果设置 device.status 与 last_poll_time，可选将状态写入 InfluxDB 用于趋势图。实时指标分为两类：（1）Linux 设备：Telegraf 在主机上采集 CPU、内存并写入 InfluxDB，后端按 host（设备名称或 IP）查询最近数据，与 device 表匹配后得到各设备 CPU/内存及数据来源（telegraf）；（2）网络设备：由外部脚本或采集程序将结果写入 Redis（如 key 为 device:R1，Hash 含 ip、cpu、mem、collect_time），后端按设备管理 IP 从 Redis 读取（支持 device:* 及按 IP 的 key 形式），与 device 表匹配后得到各设备 CPU/内存及数据来源（Redis）。为降低跨网络访问 Redis 的延迟，对 Redis 的批量读取采用 Pipeline，将多次往返合并为少量请求。前端每 10 秒自动刷新实时指标接口，展示表格与饼图，并区分数据来源。

### 5.4 告警

告警规则在 alert_rule 表中配置，包括规则名称、目标设备或设备类型（device_id 或 device_types）、指标键（metric_key，如 cpu_usage、mem_usage、device_status）、条件表达式（rule_condition，如 >80 或 offline）、严重程度（severity）、是否启用、以及可选的自动修复类型与命令。告警评估由定时任务或设备状态变更触发，读取已启用规则，根据设备列表与当前指标判断是否触发；触发时写入 alert_history（rule_id、device_id、severity、status=firing、start_time、message），并可执行 SSH 或本地脚本进行自动修复。当指标恢复时，更新对应 alert_history 的 status=resolved、end_time。告警历史支持分页查询、人工标记已处理与 CSV 导出。通知记录表仅提供查询接口，实际通知发送未实现。

### 5.5 Web SSH 与批量命令

Web SSH 基于 WebSocket：前端选择设备后建立 /api/ws/ssh?deviceId=xxx 连接，后端根据 deviceId 从 device 表获取 ip、ssh_port、ssh_user、ssh_password，与目标设备建立 SSH 连接，并将终端输入输出在 WebSocket 与 SSH 通道间双向转发。可选将会话信息（user_id、device_id、start_time、end_time、input_log）写入 webssh_session 表用于审计。批量命令功能允许用户选择多台设备并输入一条命令，后端对每台设备通过 SSH 执行该命令，汇总执行结果（成功/失败、标准输出与错误输出）返回前端展示，适用于批量巡检或配置下发。

### 5.6 配置备份与系统配置

配置备份将当前设备列表、告警规则、系统配置等序列化为 JSON，连同备份名称、类型、摘要、操作用户与时间写入 config_backup 表。恢复时根据备份 ID 读取 content，解析后按类型写回 device、alert_rule、system_config 等表，实现一键还原。系统配置存储在 system_config 表中，以 config_key 为唯一键，存储系统名称、版本、时区及千问/DeepSeek 等 API 的启用状态、端点与密钥，供前端“系统设置”页与后端 AI 服务使用。

### 5.7 AI 助手与审计导出

AI 助手为每个用户维护多会话（ai_chat_session），每条会话下有多轮消息（ai_chat_message，role 为 user 或 assistant）。用户发送消息时，后端加载该会话历史，连同当前问题调用配置好的大模型 API，将回复写入 ai_chat_message 并返回前端。审计日志在关键操作（登录、设备变更、用户变更、告警、备份等）时由 AuditService 写入 audit_log，记录 user_id、username、action、target_type、target_id、detail、ip、create_time。导出功能提供设备健康、告警历史、审计日志等的 CSV 导出接口，便于离线分析或归档。

---

## 第六章 系统测试与运行

### 6.1 测试环境

测试环境包括：本机或虚拟机安装 MySQL（nebula_ops 库）、Redis、InfluxDB；后端运行在 JDK 17 + Spring Boot 3，端口 8082；前端运行在 Node 环境（Vite），端口 5173，通过代理访问后端 /api。MySQL 中执行建表与初始化脚本（含 sys_user、role、user_role、device、alert_rule、alert_history 等），并确保 device 表具备 ssh_user、ssh_password、group_name 等列。

### 6.2 功能测试要点

功能测试覆盖：登录与登出、角色与菜单显示；设备增删改查、批量导入、健康汇总、Ping 与连通性；实时指标页中 Linux 设备与网络设备数据来源是否正确、表格与饼图是否刷新；告警规则的创建与列表、告警历史记录是否写入；Web SSH 连接与命令执行、批量命令结果汇总；配置备份的创建与恢复；系统配置的保存与读取；AI 助手的会话与消息持久化；审计日志的记录与查询；各 CSV 导出是否正常。可结合 Postman 或前端页面逐项验证，并检查 MySQL、InfluxDB、Redis 中数据是否符合预期。接口层面可重点验证：GET /api/devices、GET /api/devices/health、GET /api/metrics/realtime、GET /api/alerts/rules、GET /api/alerts/history、POST /api/auth/login、GET /api/audit 等是否返回正确结构与状态码；前端页面需验证设备列表与实时指标表格是否按数据源正确区分 Linux 与网络设备、告警历史是否分页与筛选正常、Web SSH 连接与断线是否稳定。

### 6.3 部署说明

生产部署时，MySQL、Redis、InfluxDB 建议独立部署或使用已有中间件集群；后端打包为 jar，通过 java -jar 或 systemd 运行，并配置好数据源与 InfluxDB/Redis 地址；前端执行 npm run build 后将 dist 部署至 Nginx 或其他静态服务器，并配置反向代理将 /api 与 WebSocket 转发至后端。文档中应注明默认管理员账号（admin/admin123）及首次登录后修改密码的建议，以及端口、防火墙与安全配置注意事项。

---

## 第七章 总结与展望

### 7.1 总结

本文设计并实现了基于 Spring Boot 的网络监控与运维平台，完成了设备管理、健康状态监控、实时指标展示（Linux + 网络设备双数据源）、告警规则与历史、Web SSH、批量命令、配置备份、系统配置、用户权限、操作审计与 AI 助手等核心功能。系统采用 Spring Boot 3 与 Vue 3 前后端分离架构，基于 MySQL、InfluxDB、Redis 进行数据持久化与缓存，数据库设计规范、实体关系清晰，并提供了系统逻辑流程与 E-R 关系的文字描述，便于绘制答辩用流程图与数据库关系图。平台可用于中小规模网络环境的集中监控与日常运维，具有较好的实用性与可扩展性。

### 7.2 不足与展望

当前系统仍存在以下可改进之处：（1）用户密码为明文存储，建议改为 BCrypt 等不可逆摘要并加强会话管理；（2）告警通知渠道目前主要依赖表结构预留，实际邮件、钉钉、企业微信等需进一步对接与配置；（3）InfluxDB 与 Redis 的高可用、备份策略需结合生产环境单独规划；（4）实时指标与告警评估的并发性能与限流可根据设备规模做进一步优化。后续可在上述方面进行增强，并扩展更多采集方式、可视化大屏与报表，以更好地支撑企业级运维场景。

---

**参考文献**（示例，可按学校要求调整）

[1] Spring Boot 官方文档. https://spring.io/projects/spring-boot  
[2] Vue.js 官方文档. https://vuejs.org  
[3] InfluxDB 文档. https://docs.influxdata.com  
[4] Zabbix 官方文档. https://www.zabbix.com/documentation  
[5] 张某某. 网络运维与监控系统设计与实现[J]. 计算机应用与软件, 20XX, XX(X): XX-XX.

---

### 附录：图示绘制要点

**系统逻辑流程图**：可绘制两层——（1）顶层为“用户 → 前端 → 后端 → MySQL/Redis/InfluxDB”的数据流；（2）下层按“登录/设备管理/实时指标/告警/Web SSH/备份/AI”等模块分别画出“请求 → 处理 → 数据库读写”的流程，箭头标注数据或调用方向。本章第三节与《毕业论文-图示与提纲.md》中的“一、系统逻辑流程图”一一对应，可直接按文字转成图形。

**数据库 E-R 图**：实体用矩形框表示，属性列于框内或省略仅写主键；联系用菱形或连线表示，并标注 1、n 或 M、N。重点画出：sys_user—user_role—role、role—role_permission—sys_permission、device 与 alert_rule/alert_history/monitor_item/webssh_session 的 1:n、alert_history 与 notification_log 的 1:n、ai_chat_session 与 ai_chat_message 的 1:n、config_backup 与 sys_user、audit_log 与 sys_user 的 n:1。其余独立表（system_config、alert_template）可单独放置并注明“系统配置/模板”。

*（全文字数约 10,500 字；可根据学校格式要求调整章节标题层级、参考文献与附录位置，并在第五章增加关键代码片段或接口列表以进一步充实内容。）*
