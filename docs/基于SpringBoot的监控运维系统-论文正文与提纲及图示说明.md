# 基于 Spring Boot 的监控运维系统

**论文题目**：基于 Spring Boot 的监控运维系统

---

# 一、论文提纲（目录）

1. **第 1 章 绪论**
   - 1.1 研究背景与意义
   - 1.2 国内外研究现状
   - 1.3 课题主要工作与论文结构

2. **第 2 章 相关技术简介**
   - 2.1 Spring Boot
   - 2.2 前端与相关技术
   - 2.3 数据存储与中间件
   - 2.4 网络与协议

3. **第 3 章 系统分析**
   - 3.1 业务需求概述
   - 3.2 功能需求
   - 3.3 非功能需求

4. **第 4 章 系统设计**
   - 4.1 系统架构
   - 4.2 技术选型
   - 4.3 功能模块划分
   - 4.4 Spring Boot 在系统中的应用
   - 4.5 部署与运行模型
   - 4.6 数据库设计原则
   - 4.7 核心表概览
   - 4.8 表间关系概要
   - 4.9 主要表字段说明
   - 4.10 迁移与版本管理

5. **第 5 章 系统功能模块实现说明**
   - 5.1 设备管理模块
   - 5.2 状态监测模块
   - 5.3 指标采集模块
   - 5.4 告警管理模块
   - 5.5 Web 终端模块
   - 5.6 配置备份与审计模块
   - 5.7 用户与权限模块
   - 5.8 系统配置与 AI 助手模块
   - 5.9 仪表盘与拓扑
   - 5.10 批量命令与网络 AI 命令

6. **第 6 章 系统测试与运行环境**
   - 6.1 测试思路
   - 6.2 运行环境

7. **第 7 章 总结与展望**

**附录**：ER 图（文字版）、功能流程图（文字版）、数据库表关系图（文字版）、各模块实现说明（含关键模块用到的代码与讲解）。

---

# 二、论文正文

## 第 1 章 绪论

### 1.1 研究背景与意义

随着企业网络规模不断扩大，路由器、交换机、防火墙以及各类服务器等设备数量激增，传统依赖人工巡检和分散脚本的运维方式已难以满足实时性、可观测性与安全性的要求。本课题以“基于 Spring Boot 的监控运维系统”为题，旨在设计并实现一套对异构网络设备进行统一纳管、状态监测、指标采集与告警处置的 B/S 系统，为运维人员提供集中化的可视界面与自动化能力，降低运维成本并提升故障发现与恢复效率。

本系统的现实意义体现在：第一，通过标准化接口（SNMP、SSH/Telnet）对接不同厂商设备，实现多品牌、多类型设备的统一管理；第二，对设备在线状态与 CPU、内存等关键指标进行周期性采集与存储，为容量规划与故障预警提供数据基础；第三，基于规则引擎的告警与可选邮件通知、自动修复机制，能够缩短故障响应时间；第四，结合 Web 终端、配置备份、审计日志与角色权限控制，在提升运维效率的同时保障操作可追溯与权限最小化。

### 1.2 国内外研究现状

当前，商业与开源领域均存在大量网络管理与监控产品。商业产品如 SolarWinds、PRTG、Zabbix 等，功能全面但往往针对通用 IT 监控设计，对国内常见厂商（如华为、华三、锐捷）的深度适配与二次开发灵活性有限；开源方案如 LibreNMS、Observium、Prometheus + Grafana 等，在可扩展性与成本方面具有优势，但在告警联动、Web 终端集成与权限体系上通常需要自行整合。近年来，基于 Spring Boot、Vue 等技术的 B/S 架构运维平台逐渐成为主流，将设备管理、指标展示、告警与用户权限统一在浏览器端完成，便于跨地域与多角色协作。本课题所实现的系统采用前后端分离的 B/S 架构，以 Spring Boot 为后端核心框架，在设备管理、多协议采集、告警与权限等方面进行一体化设计，并针对华为、华三、锐捷等设备做了命令与解析适配，具有一定的工程参考价值。

从技术路线看，网络设备监控通常涉及三类数据流：设备元数据与配置信息适合用关系型数据库持久化；状态与指标的时序数据适合用时序库或缓存存储；告警事件与操作审计需要与设备、规则、用户等关联，仍以关系库为主。本系统采用 MySQL 存业务表、Redis 存设备指标缓存、InfluxDB 存设备状态时序与 Telegraf 指标，与上述划分一致；在采集策略上采用“SNMP 优先、SSH/Telnet 回退”的方式，兼顾主流网络设备与仅开放 Telnet 或 SSH 的旧设备与防火墙。

### 1.3 课题主要工作与论文结构

本课题主要完成以下工作：（1）对网络设备监控与运维管理进行需求分析，明确功能模块与用户角色；（2）设计系统总体架构与数据库模型，包括设备、告警、用户权限、审计、配置备份、AI 会话等实体及关系；（3）基于 Spring Boot 实现设备管理、状态监测、指标采集（SNMP/SSH-Telnet）、告警规则与历史、Web SSH/Telnet 终端、配置备份、操作审计、用户与角色及菜单权限、系统配置、AI 助手等核心功能；（4）对关键流程与 ER 图、功能流程图、数据库表关系进行文字化描述，便于论文插图与答辩展示。

论文其余部分安排如下：第 2 章进行需求分析；第 3 章介绍系统总体设计与技术选型；第 4 章详述数据库设计及表关系；第 5 章按功能模块说明实现要点（做什么、怎么实现，以清晰表述为主、不贴代码）；第 6 章介绍系统测试与运行环境；第 7 章总结全文并展望后续工作。附录给出 ER 图、功能流程图、数据库表关系图的文字版描述，以及各模块实现说明的归纳。

---

## 第 2 章 需求分析

### 2.1 业务需求概述

系统面向的典型用户包括：网络管理员、运维人员与只读查看人员。管理员需要完成设备增删改查、分组与标签管理、SNMP/SSH 等接入参数的配置；运维人员需要查看设备状态与 CPU/内存等指标、接收告警、通过 Web 终端登录设备执行命令，并可能执行批量命令与配置备份；只读用户仅需查看仪表盘、设备列表与告警信息，不进行修改与终端操作。系统需支持按分组筛选设备、按角色控制菜单与敏感信息（如密码）的可见性，并对关键操作记录审计日志。

从业务场景上看，网络规模扩大后设备类型多样：既有运行 Linux 的服务器，也有华为、华三、锐捷、思科等厂商的路由器、交换机与防火墙。这些设备的管理方式不一：服务器通常通过 SSH 与 Telegraf 采集指标；网络设备则多支持 SNMP，部分老旧或封闭设备仅开放 Telnet。因此系统需支持多协议、多厂商的统一纳管，并在 SNMP 不可用或未配置时能够回退到 SSH/Telnet 采集。同时，设备在线状态的判定不能仅依赖 ICMP，需对网络设备增加 TCP 端口（如 161、22、23）探测，以保证列表与 Ping 结果一致。在交互层面，管理 IP 须符合 IPv4 规范才能保存，设备列表在数据量较大时需分页展示（如每页 10 条）。

### 2.2 功能需求

**设备管理**：支持设备的添加、编辑、逻辑删除与列表展示；设备属性包括名称、管理 IP（IPv4 规范）、类型（服务器、路由器、交换机、防火墙、其他）、厂商与型号、分组、SSH/Telnet 端口与凭据、SNMP 版本与团体名或 v3 安全配置等；支持按分组筛选与分页展示；支持单设备 Ping 检测，并将检测结果回写设备状态与最后轮询时间。

**状态监测**：对未删除设备进行周期性的可达性检测（ICMP 与针对网络设备的 TCP 端口探测），更新设备状态（正常、告警、严重、离线）与最后轮询时间，并将状态时序写入 InfluxDB（若已配置）；状态变化时可触发告警规则。

**指标采集**：Linux 服务器通过 Telegraf（部署在服务器上、把本机 CPU/内存写入 InfluxDB）或由系统 SSH 回退采集；网络设备由 **DeviceSnmpCollectService** 优先 SNMP 采集，失败则回退 **DeviceSshCollectService** 用 SSH/Telnet 执行厂商命令并解析结果。采集结果写入**内存缓存**（本进程内）与 **Redis**（供“设备指标”页等接口按 IP 读）；**实时指标接口**即前端“设备指标”页调用的接口，汇总上述来源后返回。支持“刷新”触发一次采集，定时采集间隔可配置（如 5 分钟）。

**告警管理**：支持创建告警规则，按设备或设备类型、指标键、条件与严重程度配置，并可启用邮件通知与自动修复；系统定期根据当前指标与设备状态评估规则，产生告警历史（触发/恢复），并支持查询与导出。

**Web 终端**：支持通过浏览器建立与设备的 SSH 或 Telnet 会话，使用设备管理中配置的凭据登录；Telnet 支持自动识别登录与密码提示并发送凭据；支持 SSH/Telnet 保活，减少设备侧空闲断开会话；终端输出可被解析并写入设备指标缓存。

**配置备份与审计**：支持对设备列表、告警规则、系统配置等进行备份与恢复，备份记录存储于数据库；对设备与告警等关键操作记录审计日志（操作人、动作、对象、详情、IP、时间），支持按条件查询。

**用户与权限**：用户与角色多对多关联；角色可配置可分配菜单；用户可见菜单为所属角色可分配菜单的并集；敏感字段（如设备 SSH/SNMP 密码）仅对管理员角色可见与可编辑。

**系统配置与 AI 助手**：系统配置表存储键值对（如系统名称、API 密钥等）；AI 助手支持会话与消息持久化，并可结合设备列表、健康汇总与指标快照等上下文进行问答。

此外，系统提供仪表盘（设备健康汇总、在线/离线时间线、设备类型分布、告警摘要）、设备拓扑视图、批量命令执行、以及基于大模型的网络 AI 命令推荐与问答，与设备管理、指标采集、告警与权限共同构成完整的运维管理闭环。

### 2.3 非功能需求

系统应具备较好的可用性与可维护性：关键采集与检测任务采用定时调度与异步执行，避免阻塞主流程；前后端分离便于独立部署与扩展；数据库采用 Flyway 进行版本化迁移，表结构与实体保持一致。安全性方面：用户密码加密存储、接口按角色与菜单权限控制、敏感信息脱敏展示、操作审计留痕。性能方面：设备列表分页、指标与告警数据按需查询与聚合，InfluxDB 与 Redis 用于时序与缓存以减轻关系库压力。

---

## 第 3 章 系统总体设计

### 3.1 系统架构

系统采用前后端分离的 B/S 架构。前端为基于 Vue 3 的单页应用，负责页面渲染、路由、表单校验与用户交互，通过 HTTP 与 WebSocket 与后端通信；后端基于 Spring Boot 提供 REST 接口与 WebSocket 服务，负责业务逻辑、数据持久化、与设备及外部组件（InfluxDB、Redis、邮件、大模型 API）的交互。设备状态监测、SNMP 采集、SSH/Telnet 采集等由 Spring 定时任务或异步任务触发；告警评估由定时任务根据当前指标与规则写入告警历史；Web 终端通过 WebSocket 维持长连接，后端将 SSH/Telnet 会话的输入输出与浏览器双向转发。

### 3.2 技术选型

后端：Java 21、Spring Boot、Spring Data JPA、Flyway、JSch（SSH）、Apache Commons Net（Telnet）、SNMP 库、InfluxDB 客户端、Redis、Lombok 等。前端：Vue 3、Vue Router、Axios、xterm.js（终端）、ECharts（图表）等。数据库：MySQL 存储业务数据；Redis 存储设备指标缓存；InfluxDB 存储设备状态时序与 Telegraf 指标。部署：后端可打包为可执行 JAR，前端构建为静态资源由后端或 Nginx 提供；支持通过 application.yml 或环境变量配置数据库、Redis、InfluxDB、邮件、采集间隔等。

### 3.3 功能模块划分

系统划分为以下功能模块：设备管理、状态监测、指标采集（含 SNMP 与 SSH/Telnet）、告警管理、Web 终端、配置备份、操作审计、用户与角色及菜单权限、系统配置、AI 助手。仪表盘、设备拓扑、批量命令、网络 AI 命令等作为设备与监控的延伸展示与操作入口，归入相应模块或单独作为子模块。各模块共享设备、用户、角色等基础数据，通过服务层与控制器组织接口与权限。前端菜单编码与后端保持一致，包括：仪表盘、设备列表、拓扑、批量命令、网络 AI 命令、告警、设备指标、AI 助手、操作日志、配置备份、用户管理、系统设置；角色可分配菜单存储在 role_menu 表中，用户登录后可见菜单为其所属角色可分配菜单的并集。

### 3.4 Spring Boot 在系统中的应用

本系统基于 Spring Boot 构建，充分利用其核心特性：（1）自动配置：数据源、JPA、Redis、Flyway 等通过引入对应 Starter 与配置文件即可完成集成。（2）依赖注入与分层：Controller、Service、Repository 分层清晰，由 Spring 容器统一管理生命周期与依赖关系。（3）定时任务：设备状态检测、SNMP/SSH 采集、告警评估等通过定时任务声明周期与间隔，与业务 Service 解耦。（4）条件装配：部分功能（如 SNMP 采集、SSH 采集）通过配置开关控制是否加载。（5）WebSocket：Web 终端采用 Spring WebSocket 与处理器，与 REST 接口共用同一应用。（6）事务与异常：关键写操作使用事务保证数据一致性；可与 Spring MVC 结合统一返回格式与错误信息。上述应用体现了基于 Spring Boot 开发在监控运维系统中的作用：快速集成、规范分层、可配置、易扩展。

### 3.5 部署与运行模型

后端以 Spring Boot 可执行 JAR 形式运行，内嵌 Tomcat 提供 REST 与 WebSocket；前端构建后的静态资源可置于 JAR 同目录或由 Nginx 反向代理。数据库连接、Redis、InfluxDB、邮件、SNMP/SSH 采集开关与间隔等均通过配置文件或环境变量配置，便于开发、测试与生产环境分离。定时任务的间隔与超时均可调，在保证数据新鲜度的同时避免对设备与网络造成过大压力。

---

## 第 4 章 数据库设计

### 4.1 设计原则

数据库设计遵循以下原则：实体与表一一对应，主键统一为自增 BIGINT；枚举型字段使用 ENUM 或 VARCHAR 与后端枚举转换器对应；时间字段统一为 DATETIME；逻辑删除采用 deleted 标记；多对多关系通过中间表（如 user_role、role_menu）维护；表结构与 Flyway 迁移脚本及 JPA 实体保持一致，便于版本管理与对比。

### 4.2 核心表概览

**设备与监控**：device（设备主表，含 IP、类型、厂商、SNMP/SSH 配置、状态、最后轮询时间等）；设备状态时序写入 InfluxDB，不在此关系库中建表。

**告警**：alert_rule（告警规则）、alert_history（告警历史）、alert_template（告警模板，可选）。

**用户与权限**：sys_user（用户）、role（角色）、user_role（用户-角色）、role_menu（角色可分配菜单）。

**审计与配置**：audit_log（审计日志）、system_config（系统配置）、config_backup（配置备份）。

**AI**：ai_chat_session（AI 会话）、ai_chat_message（AI 消息）。

### 4.3 表间关系概要

device 与 alert_rule、alert_history 通过 device_id 关联；alert_history 与 alert_rule 通过 rule_id 关联。sys_user 与 role 通过 user_role 多对多关联；role 与菜单通过 role_menu 关联（存储菜单编码）。audit_log、config_backup 通过 username 或 user_id 与用户概念关联，可不建外键。ai_chat_message 通过 session_id 与 ai_chat_session 关联。device 为独立核心实体，与告警、采集、Web 终端等通过业务逻辑与 IP/ID 关联，不强制外键。详细关系见附录“数据库表关系图（文字版）”。

### 4.4 迁移与版本管理

表结构变更通过 Flyway 迁移脚本管理，按版本号顺序执行。实体与表结构对照文档列出各表列名、类型与注释，并注明已删除的列或表，便于与现有库对比修补。采用逻辑删除（device.deleted）时，查询均过滤已删除记录，避免统计偏差。

---

## 第 5 章 功能模块实现说明

（各模块“做什么、怎么实现”的清晰阐述见附录“各模块实现说明（无代码）”；此处仅作概要。）

**设备管理**由 **DeviceController**（接收前端请求）与 **DeviceService**（执行业务逻辑）完成 CRUD、分组筛选与分页；新增与编辑时对管理 IP 做 IPv4 校验，保存时对敏感字段在非管理员留空时保留原值；单设备 Ping 接口（用途是让用户点击 Ping 后立即得到在线/离线与 RTT）调用可达性检测并更新设备状态与最后轮询时间，前端刷新列表后即可看到与 Ping 结果一致的状态。**状态监测**由 **MonitorService**（用途是周期判断设备是否在线、更新状态并触发上下线告警）定时执行可达性检测：先 ICMP，失败则对非服务器类型设备依次尝试 TCP 161、设备 SSH/Telnet 端口、23；根据结果更新设备状态并落库，状态变化时触发告警回调；可选将状态时序写入 InfluxDB（供仪表盘时间线等使用）。指标采集方面，**DeviceSnmpCollectService**（SNMP 采集服务，用途是向网络设备发 SNMP 请求取 CPU/内存）对已配置 SNMP 的网络设备进行采集，失败时若设备配置了 SSH/Telnet 则回退至 **DeviceSshCollectService**（SSH 采集服务，用途是登录设备执行 display/show 命令并解析输出）单次采集；无 SNMP 设备时也可主动调用 SSH 采集，目的是让只配 SSH 的在线设备也在指标页有数据。SSH 采集结果写入**内存缓存**（本进程内，供刚采完即读）与 **Redis**（供“设备指标”页等按 IP 读）。**实时指标接口**（供前端“设备指标”页调用）优先从 SSH 缓存按 IP 取，再按设备从 Redis 取，与 Telegraf/InfluxDB 的 Linux 指标一起返回前端。**告警管理**由 **AlertService**（用途是根据规则与当前状态/指标产生告警记录、发邮件或执行自动修复）在设备状态变化时写入设备上下线类告警，定时任务根据当前指标与规则进行阈值判断，写入或恢复告警历史；支持邮件通知与自动修复。**Web 终端**由 **WebSshService**（建立 SSH/Telnet 会话）根据设备端口 22/23 建立会话，Telnet 自动发送用户名与密码完成登录；SSH 配置保活、Telnet 定时发送保活报文，减少设备侧空闲断开；**SshWebSocketHandler**（维护浏览器 WebSocket 与后端会话的映射）将用户输入转发至设备、设备输出转发至前端，并可对输出进行解析并写入设备指标缓存（供设备指标页使用）。**ConfigBackupService**（配置备份服务，用途是一键备份/恢复设备与配置快照）将设备、告警规则、系统配置等序列化为 JSON 存储于配置备份表；恢复时按类型解析并写回相应表。**AuditService**（审计服务，用途是记录谁在何时对何对象做了何操作）在设备与告警等关键操作后写入审计日志。用户与角色通过 **user_role**（用户-角色中间表）多对多关联，角色可分配菜单存储在 **role_menu**（用途是控制用户登录后能看到哪些菜单）；登录后返回用户信息及角色与菜单列表，前端根据菜单渲染侧栏与路由；设备密码等敏感字段仅对管理员角色展示与可编辑。系统配置以键值对存储；**AiChatService**（AI 助手服务）在调用大模型前注入设备列表、健康汇总与指标快照等上下文。**仪表盘**（总览设备健康、时间线、告警）聚合设备健康汇总、在线/离线时间线、设备类型饼图、告警摘要与最近告警列表；**设备拓扑**从设备列表读取数据按类型与状态渲染节点（用途是图形化展示设备关系）。**BatchCommandService**（批量命令服务，用途是对多台设备依次执行命令并汇总结果）与网络 AI 命令复用设备管理与 SSH/Telnet 采集的凭据与连接逻辑，不单独建表。

---

## 第 6 章 系统测试与运行环境

### 6.1 测试思路

系统测试可从功能测试、接口测试、集成验证、兼容性等方面进行。功能测试对设备 CRUD、分组筛选、分页、Ping 及状态回写、告警规则与历史、Web 终端、配置备份、用户与角色及菜单、审计日志等逐项验证。接口测试对主要 REST 接口与 WebSocket 连接进行请求与响应校验，包括参数校验、权限控制与异常返回。集成验证在配置 MySQL、Redis 及可选 InfluxDB 与邮件后，运行定时任务与采集流程，确认设备状态与指标能正确更新并展示；验证 SNMP 失败时 SSH 回退与无 SNMP 设备时 SSH 采集触发。兼容性可在不同浏览器与分辨率下检查前端展示与操作；对华为、华三、锐捷等设备实际接入，验证命令解析与指标展示。

### 6.2 运行环境

开发与运行需 JDK 21、Maven、Node.js（前端构建）；MySQL 5.7 或 8.x、Redis（可选）、InfluxDB（可选）。配置在 application.yml 中完成；采集间隔、超时等可通过同一配置文件或环境变量覆盖。部署时后端打包为可执行 JAR，前端构建后可将静态资源拷贝至后端静态资源目录或由 Nginx 托管；单机即可运行，如需高可用可多实例部署并共享 MySQL/Redis。

---

## 第 7 章 总结与展望

本课题基于 Spring Boot 设计并实现了一个面向网络设备监控与运维管理的 B/S 系统，完成了设备管理、状态监测、多协议指标采集、告警规则与历史、Web SSH/Telnet 终端、配置备份、审计、用户与角色及菜单权限、系统配置与 AI 助手等核心功能，并对华为、华三、锐捷等厂商设备做了命令与解析适配。数据库采用 MySQL 存储业务数据，通过 Flyway 管理表结构迁移，与 JPA 实体保持一致；ER 图、功能流程图与数据库表关系图已以文字形式整理，便于绘图与答辩展示。在实现过程中，针对设备列表 Ping 成功但仍显示离线、无 SNMP 设备时仍希望对在线设备进行 SSH 采集、Web 终端空闲断开会话、设备指标页看不到新添加设备等问题，通过将 Ping 结果回写设备状态、在无 SNMP 设备时主动触发 SSH 采集、为 SSH/Telnet 增加保活、以及明确设备类型须为路由器/交换机/防火墙才能在网络设备列表中展示等设计，提升了系统的可用性与可理解性。设备管理表单中增加 IPv4 格式校验与分页展示，进一步保证了数据规范与交互效率。后续可在增加更多厂商与指标类型的适配、告警渠道（如企业微信、钉钉）、更丰富的仪表盘与报表、以及性能与安全方面的专项优化与测试等方面继续完善。

---

# 三、ER 图（文字版，供编辑与绘图）

以下用文字描述实体、属性及关系，便于在 draw.io、Visio、PlantUML 等工具中绘制 ER 图。

**实体列表与主要属性**

- **device（设备）**：id（主键），name，ip，type，vendor，model，snmp_version，snmp_community，snmp_security，snmp_port，ssh_port，ssh_user，ssh_password，remark，group_name，status，last_poll_time，create_time，update_time，deleted。
- **alert_rule（告警规则）**：id（主键），name，device_id（可选，关联 device），device_types，metric_key，rule_condition，severity，enabled，notify_email，auto_fix_*，create_time，update_time。
- **alert_history（告警历史）**：id（主键），rule_id（关联 alert_rule），device_id（关联 device），metric_key，trigger_value，start_time，end_time，status，severity，message，create_time，update_time。
- **alert_template（告警模板）**：id（主键），name，metric_key，rule_condition，severity，device_types，create_time，update_time。
- **sys_user（用户）**：id（主键），username（唯一），password，email，enabled，create_time，update_time。
- **role（角色）**：id（主键），code（唯一），name（唯一），description，create_time，update_time。
- **user_role（用户-角色）**：user_id（主键之一，关联 sys_user），role_id（主键之一，关联 role）；多对多中间表。
- **role_menu（角色-菜单）**：id（主键），role_id（关联 role），menu_code；唯一约束 (role_id, menu_code)。
- **audit_log（审计日志）**：id（主键），username，action，target_type，target_id，detail，ip，create_time。
- **system_config（系统配置）**：id（主键），config_key（唯一），config_value，remark，create_time，update_time。
- **config_backup（配置备份）**：id（主键），name，backup_type，summary，content，user_id（可关联 sys_user），create_time。
- **ai_chat_session（AI 会话）**：id（主键），username，title，create_time。
- **ai_chat_message（AI 消息）**：id（主键），session_id（关联 ai_chat_session），role，content，create_time。

**关系描述（用于画连线）**

- alert_rule 可引用 device（device_id，可选）；alert_history 引用 alert_rule（rule_id）与 device（device_id）。
- sys_user 与 role 通过 user_role 多对多（user_id，role_id）。
- role 与“菜单”通过 role_menu 一对多（一个角色对应多条 role_menu 记录，每条记录一个 menu_code）。
- ai_chat_message 多对一 ai_chat_session（session_id）。
- config_backup 可逻辑关联 sys_user（user_id）；audit_log 逻辑关联用户（username）与操作对象（target_type、target_id）；不强制建外键时可画虚线或注释说明。

**绘图建议**：每个实体画为一个矩形，内写实体名与主要属性；从“多”的一方画箭头指向“一”的一方，箭头上标外键名（如 rule_id、device_id、session_id）；user_role 置于 sys_user 与 role 之间，role_menu 置于 role 与“菜单”之间；device 与 InfluxDB/Redis 无表级外键，可在图例中说明“业务逻辑关联”。

---

# 四、功能流程图（文字版，供编辑与绘图）

以下用文字描述主要业务流程，便于绘制流程图（可用矩形表示步骤、菱形表示判断、箭头表示流向）。

**流程图 1：用户登录与权限**（用途：验证身份并确定用户能访问哪些菜单与功能）

- 开始 → 用户输入用户名、密码 → 后端校验用户是否存在且密码正确、账号是否启用 → 若失败则返回错误信息，结束。
- 若成功 → 查询该用户关联的角色（**user_role**，即“该用户拥有哪些角色”）→ 查询这些角色对应的可分配菜单（**role_menu**，即“这些角色分别能访问哪些菜单”）→ 求并集得到用户可见菜单编码列表 → 返回用户信息、角色列表与菜单列表给前端 → 前端根据菜单**渲染侧栏与路由**（即左侧显示哪些菜单、能打开哪些页面）、根据角色控制敏感信息（如设备密码）展示 → 结束。

**流程图 2：设备添加与状态更新**（用途：把设备信息入库，并周期或按需更新在线状态与最后轮询时间）

- 开始 → 用户填写设备表单（名称、IP、类型、SSH/SNMP 等）→ 前端校验 IP 是否为规范 IPv4（四段 0～255）→ 若不通过则提示错误，结束。
- 若通过 → 提交至后端（DeviceController/DeviceService）→ 后端保存至 device 表，新设备默认 status=离线、deleted=否（逻辑删除标记）→ 返回成功，结束。
- 状态更新分支：定时任务（MonitorService）或用户点击 Ping → 读取设备 IP 与类型 → 先尝试 ICMP 可达 → 若失败且设备类型非服务器，则依次尝试 TCP 161、设备 ssh_port、23 → 根据是否可达及 RTT 更新 device 的 status、last_poll_time 并保存 → 若状态发生变化则触发告警回调（AlertService，用于产生设备上下线告警）→ 结束。

**流程图 3：指标采集与展示**（用途：为“设备指标”页与告警规则提供各设备的 CPU、内存等数据）

- 定时或用户点击“刷新”触发 → 判断设备类型：若为服务器，则从 InfluxDB（Telegraf 写入）或 SSH 回退取 CPU/内存；若为网络设备，则先尝试 SNMP 采集（DeviceSnmpCollectService，向设备要 OID 数据）。
- SNMP 采集：对已配置 SNMP 的设备请求 CPU/内存 OID → 成功则解析并写入 Redis（供实时指标接口按 IP 读）/内存缓存（供刚采完即读）；失败则若设备已配置 SSH/Telnet 则**回退**调用 DeviceSshCollectService 单次采集（即登录设备执行命令取指标）。
- 若无任何设备配置 SNMP，则直接对在线且已配置 SSH 的网络设备执行 SSH 采集（目的：让只配 SSH 的设备也在指标页有数据）。
- SSH 采集：按设备厂商选择 display/show 命令，通过 SSH 或 Telnet 执行，解析输出中的 CPU、内存百分比，写入内存缓存与 Redis（按 IP 为键，供实时指标接口读取）。
- **实时指标接口**（供前端“设备指标”页调用）：汇总 Telegraf/InfluxDB 的 Linux 指标（按设备 ID），再对网络设备按 IP 从 SSH 缓存或 Redis 取指标，合并后返回前端 → 前端在设备指标页按“Linux 设备”与“网络设备”分别展示，结束。

**流程图 4：告警触发与恢复**（用途：在设备上下线或指标超阈值时产生告警记录，并可发邮件或执行自动修复）

- 设备状态变化：监测任务（MonitorService）更新设备状态后 → 若由在线变为离线或由离线变为在线 → **AlertService**（告警服务）写入一条告警历史（设备上下线类），结束。
- 指标阈值告警：定时任务启动 → 读取所有启用的告警规则（即“什么情况算告警”）→ 对每条规则按 device_id 或 device_types 匹配设备 → 读取这些设备当前指标（从缓存/Redis 等）→ 判断是否满足规则条件（如 CPU>80）→ 若满足且该设备该规则当前无“触发中”的告警历史，则插入一条 alert_history（status=触发中）；若满足且已有触发中记录则跳过 → 若不满足且该规则该设备此前有触发中记录，则更新该条历史的 end_time 与 status=已恢复 → 若规则启用邮件通知则在触发时调用邮件服务（AlertEmailService）→ 结束。

**流程图 5：Web 终端连接**（用途：在浏览器里打开设备的 SSH/Telnet 终端，用户无需单独开终端软件）

- 开始 → 用户选择设备并点击连接 → 前端建立 **WebSocket**（长连接，用于浏览器与后端实时双向传数据）连接（携带 deviceId）→ 后端根据 deviceId 查询设备，校验是否存在、未删除、已填 SSH 用户名与密码 → 若否则返回错误并关闭连接，结束。
- 若是 → **WebSshService** 根据设备端口判断：若为 23 则建立 Telnet 连接，否则建立 SSH 连接 → 连接成功后 Telnet 自动发送回车与用户名、密码完成登录；SSH 打开 Shell 通道 → **SshWebSocketHandler** 启动两条线程：一条将设备输出转发至 WebSocket（用户能在页面上看到设备输出），一条将 WebSocket 接收的用户输入转发至设备（用户按键传到设备）→ 可选：对输出进行缓冲与解析，满足条件时写入**设备指标缓存**（供设备指标页展示，例如用户在终端里执行了 display cpu-usage 后指标页可更新）→ SSH 侧配置保活间隔，Telnet 侧定时发送保活报文，直至用户断开或连接异常，结束。

---

# 五、数据库表关系图（文字版，供编辑与绘图）

以下用文字描述各表之间的主键、外键及引用关系，便于绘制“数据库表关系图”。

**表清单与主键**

- device：主键 id。
- alert_rule：主键 id；可选引用 device_id → device.id。
- alert_history：主键 id；rule_id → alert_rule.id；device_id → device.id。
- alert_template：主键 id；无外键。
- sys_user：主键 id。
- role：主键 id。
- user_role：联合主键 (user_id, role_id)；user_id → sys_user.id；role_id → role.id。
- role_menu：主键 id；唯一 (role_id, menu_code)；role_id → role.id。
- audit_log：主键 id；无外键（username、target_type、target_id 为逻辑关联）。
- system_config：主键 id；唯一 config_key。
- config_backup：主键 id；user_id 可关联 sys_user.id（可选外键）。
- ai_chat_session：主键 id；无外键（username 为字符串）。
- ai_chat_message：主键 id；session_id → ai_chat_session.id。

**关系矩阵（谁引用谁）**

- device：被 alert_rule（device_id 可选）、alert_history（device_id）引用。
- alert_rule：被 alert_history（rule_id）引用。
- sys_user：被 user_role（user_id）、config_backup（user_id 可选）引用。
- role：被 user_role（role_id）、role_menu（role_id）引用。
- ai_chat_session：被 ai_chat_message（session_id）引用。
- 其余表：audit_log、system_config、alert_template 不引用其他业务表。

**绘图建议**：以“表名”为矩形，内写主键及主要字段名；从“多”的一方画箭头指向“一”的一方，箭头上标外键名（如 rule_id、device_id、session_id）；user_role、role_menu 作为中间表放在相应两表之间；图例中可说明 device 与 InfluxDB/Redis 无表级外键，仅为业务关联。

---

# 六、各模块实现说明（含关键代码讲解）

以下说明各模块的作用与实现思路；**仅对实际用到的关键逻辑附代码片段并做简要讲解**，便于论文阐述与答辩。

**设备管理模块**  
本模块负责设备的增删改查与列表展示，是系统的基础数据来源。实现上，后端提供按分组查询设备列表的接口，返回未逻辑删除的设备；前端支持按分组下拉筛选，并对列表按 IP、类型、状态等排序后分页展示（如每页 10 条）。添加与编辑设备时，前端对管理 IP 做格式校验，必须是四段数字且每段 0～255 的 IPv4 才允许提交；后端保存时对 SSH 密码、用户名等敏感字段做保护：若提交的密码或用户名为空，则用数据库中已有值覆盖后再保存，避免误清空导致 Web 终端无法登录。单设备 Ping 由专门接口处理：后端根据设备 ID 执行可达性检测并将结果写回设备状态与最后轮询时间，前端刷新列表即可看到与 Ping 一致的状态。

*关键代码（设备更新时保留密码、Ping 回写状态）*  
更新设备时，若请求体中 SSH 密码或用户名为空，则用库中已有值覆盖，再保存，避免编辑时误清空导致 Web 终端连不上：

```java
if (device.getSshPassword() == null || device.getSshPassword().isBlank()) {
    device.setSshPassword(existing.getSshPassword());
}
if (device.getSshUser() == null || device.getSshUser().isBlank()) {
    device.setSshUser(existing.getSshUser());
}
deviceService.save(device);
```

Ping 接口调用“可达性检测并更新状态”的服务方法，返回 RTT 与 up/down，供前端展示并刷新列表：

```java
@GetMapping("/{id}/ping")
public ResponseEntity<Map<String, Object>> ping(@PathVariable Long id) {
    long rtt = deviceService.pingAndUpdateStatus(id);
    return ResponseEntity.ok(Map.of(
        "deviceId", id, "rttMs", rtt, "status", rtt >= 0 ? "up" : "down"));
}
```

**状态监测模块**  
本模块负责周期性地判断每台设备是否可达，并更新设备状态与最后轮询时间。实现上，由定时任务按配置的间隔（如 5 分钟）触发，遍历所有未删除的设备；对每台设备先尝试 ICMP（即 Ping）探测，若成功则根据 RTT（往返时延，毫秒）将状态设为正常或告警，若失败则仅对类型为路由器、交换机、防火墙或其他的设备再依次尝试 TCP 连接端口 161（SNMP）、设备配置的 SSH/Telnet 端口、以及 23（Telnet），任一连通即视为在线并记录 RTT。状态与最后轮询时间写回设备表；若配置了 InfluxDB 则同时写入状态时序点；若与上次状态不同则调用告警服务做设备上下线类告警。

*关键代码（可达性判断：ICMP 失败后对网络设备做 TCP 回退）*  
先 Ping（ICMP），失败则对非 server 设备依次尝试 TCP 连接：161 为 SNMP 端口、sshPort 为设备配置的 SSH/Telnet 端口、23 为 Telnet 端口，任一连通即判在线，用于解决“能 ping 通却显示离线”或禁 Ping 环境下误判离线的问题：

```java
private long resolveReachable(Device d) {
    long rtt = ping(ip);
    if (rtt >= 0) return rtt;
    if (type == Device.DeviceType.server) return -1;
    long tcpRtt = tcpReachable(ip, snmpPort, tcpTimeout);
    if (tcpRtt >= 0) return tcpRtt;
    tcpRtt = tcpReachable(ip, sshPort, tcpTimeout);
    if (tcpRtt >= 0) return tcpRtt;
    if (sshPort != 23) { tcpRtt = tcpReachable(ip, 23, tcpTimeout); if (tcpRtt >= 0) return tcpRtt; }
    return -1;
}
```

根据 RTT（往返时延；<0 表示不可达）更新设备状态并落库：rtt>800 毫秒设为 warning（告警），否则正常；状态变化时触发告警回调（本系统实际使用的逻辑）：

```java
DeviceStatus newStatus = rtt < 0 ? DeviceStatus.offline
    : (rtt > 800 ? DeviceStatus.warning : DeviceStatus.normal);
d.setStatus(newStatus);
deviceRepository.save(d);
if (alertService != null && previousStatus != newStatus) {
    alertService.onDeviceStatusChange(d, previousStatus, newStatus);
}
```

**指标采集模块**  
本模块负责采集设备的 CPU、内存等指标，**用途**是供前端“设备指标”页展示与告警规则判断阈值。实现上分为两条线：一是 **Linux 服务器**，指标主要来自 Telegraf（部署在服务器上的采集程序，把本机 CPU、内存等写入 InfluxDB）或本系统 SSH 回退采集；二是**网络设备**，由 **DeviceSnmpCollectService**（SNMP 采集服务）优先发 SNMP 请求取 CPU/内存，失败则回退到 **DeviceSshCollectService**（SSH/Telnet 采集服务）在设备上执行 display/show 命令并解析结果，写入**内存缓存**（本进程内，刚采完可立刻被读）与 **Redis**（供“设备指标”页等接口按 IP 读取）。当没有任何设备配置 SNMP 时，定时任务仍会主动调用 SSH 采集，目的是让只配了 SSH 的在线网络设备也能在指标页看到数据。用户点击“刷新”时触发一次采集，**实时指标接口**（即前端“设备指标”页调用的接口）会汇总 InfluxDB 的 Linux 指标与缓存/Redis 的网络设备指标返回，接口先返回当前已有数据，新采到的数据下次刷新可见。

*关键代码（无 SNMP 设备时仍触发 SSH 采集；SNMP 失败时回退 SSH）*  
无已配置 SNMP 的设备时，不直接跳过，而是调用 **DeviceSshCollectService**（SSH 采集服务，用途是登录设备执行 display/show 命令并解析 CPU、内存）对在线且已配置 SSH 的网络设备做一次采集，这样“只配 SSH、没配 SNMP”的设备也能在设备指标页看到数据（本系统实际使用）：

```java
if (devices.isEmpty()) {
    DeviceSshCollectService ssh = sshCollectProvider.getIfAvailable();
    if (ssh != null) {
        int n = ssh.collectAllNow();
        if (n > 0) log.info("无 SNMP 设备时已通过 SSH/Telnet 采集 {} 台在线网络设备", n);
    }
    return;
}
```

SNMP 单机采集异常时，若设备已配置 SSH/Telnet 则**回退**一次 SSH 采集（即用 DeviceSshCollectService 对该设备执行命令取指标）并写 Redis（供实时指标接口按 IP 读取）：

```java
try {
    collectOneAndSave(d);
} catch (Exception e) {
    tryFallbackToSsh(d);  // 调用 SSH 采集服务单次采集并写 Redis
}
```

**告警管理模块**  
本模块负责根据规则与当前设备状态、指标产生告警历史，并可发送邮件或执行自动修复。设备状态发生变化时由状态监测模块回调告警服务，写入“设备上下线”类告警历史；另有定时任务根据指标与规则做阈值判断，写入或恢复告警历史；若规则开启邮件通知则在触发时调用邮件服务。

*关键代码（设备状态变化时写入上下线告警）*  
监测更新设备状态后若发现由在线变离线或由离线变在线，则查找 metric_key 为 device_status 的启用规则，按设备类型过滤后写入一条告警历史，并可选执行自动修复与发送邮件（本系统实际使用）：

```java
public void onDeviceStatusChange(Device device, DeviceStatus previousStatus, DeviceStatus newStatus) {
    if (newStatus == DeviceStatus.offline) event = "offline";
    else if (previousStatus == DeviceStatus.offline && newStatus != DeviceStatus.offline) event = "online";
    if (event == null) return;
    List<AlertRule> rules = alertRuleRepository.findByMetricKeyAndEnabledTrue("device_status");
    for (AlertRule rule : rules) {
        // 按规则条件与设备类型匹配后
        AlertHistory history = AlertHistory.builder()
            .ruleId(rule.getId()).deviceId(device.getId()).metricKey("device_status")
            .triggerValue(event).startTime(LocalDateTime.now()).status(AlertHistory.AlertStatus.firing /* 即“告警触发中” */).message(msg).build();
        alertHistoryRepository.save(history);
        if (rule.getNotifyEmail()) alertEmailService.sendAlertEmailAsync(history);
    }
}
```

**Web 终端模块**  
本模块负责在浏览器中提供与设备的 SSH 或 Telnet 会话。后端根据设备端口 22 或 23 选择 SSH 或 Telnet；SSH 侧配置保活（即每隔一定秒数向设备发一次“我还连着”的探测，避免长时间不操作被设备踢掉；连续多次无响应后才认为真断开），Telnet 侧自动登录并定时发送保活报文；用两条线程分别转发设备输出到 WebSocket 与 WebSocket 输入到设备；终端输出可被解析并写入设备指标缓存。连接失败时仅对“连接/网络”类失败标记为“曾连不上”，未配置凭据不标记。

*关键代码（SSH 保活配置、Telnet 自动登录）*  
建立 SSH 会话时设置保活参数，避免用户暂时不敲命令时被设备当成“人已离开”而踢下线。含义用大白话说：**ServerAliveInterval（保活间隔）** 表示“每隔多少秒向设备发一次‘我还在线’的小包”，例如 45 表示每 45 秒发一次，设备收到后就不会因空闲而断开；**ServerAliveCountMax（保活重试次数）** 表示“连续多少次收不到设备回应才认为连接真的断了”，例如 3 表示连发 3 次都没回应才判定断开。本系统实际使用如下：

```java
Properties config = new Properties();
config.put("StrictHostKeyChecking", "no");
config.put("ServerAliveInterval", "45");   // 每 45 秒发一次保活，避免长时间无操作被设备踢掉
config.put("ServerAliveCountMax", "3");    // 连续 3 次无响应才认为断开
session.setConfig(config);
session.connect(10000);
```

Telnet 连接成功后，用设备管理中的用户名与密码自动完成登录，无需用户在终端再输入：

```java
telnet.connect(ip, port);
telnetDoLogin(telnetIn, telnetOut, out, user, pass);  // 识别 Username/Password 提示并发送
```

**配置备份与审计模块**  
配置备份将设备列表、告警规则、系统配置等序列化为 JSON 存入备份表，恢复时按类型解析并写回。审计在设备与告警等关键操作后写入操作人、动作、对象类型与 ID、详情、IP、时间，支持按条件查询。

*关键代码（审计：在请求线程取用户与 IP，异步落库）*  
先在调用线程中从当前请求取用户名与 IP（避免异步线程拿不到请求上下文），再通过 @Async 异步写入审计表（即另起线程落库，不阻塞当前请求；本系统实际使用）：

```java
public void log(String action, String targetType, Long targetId, String detail) {
    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
        HttpServletRequest req = attrs.getRequest();
        if (req != null) {
            ip = req.getHeader("X-Forwarded-For"); if (ip == null) ip = req.getRemoteAddr();
            String u = req.getHeader("X-User-Name"); if (u != null && !u.isEmpty()) username = u;
        }
    }
    saveAsync(username, ip, action, targetType, targetId, detail);
}
@Async
void saveAsync(...) {
    AuditLog entry = AuditLog.builder().username(username).action(action)
        .targetType(targetType).targetId(targetId).detail(detail).ip(ip).build();
    auditLogRepository.save(entry);
}
```

**用户与权限模块**  
本模块负责用户认证与按角色的菜单、敏感信息控制。实现上，用户表与角色表通过用户-角色中间表多对多关联；角色与“可分配菜单”通过角色-菜单表关联，表中存菜单编码（与前端菜单一致）。用户登录时校验用户名密码与启用状态，通过后查询其关联角色及这些角色下的可分配菜单编码，求并集后随用户信息一并返回前端；前端根据菜单列表渲染侧栏与路由，根据角色（如是否为管理员）决定是否展示或可编辑设备密码等敏感字段。后端在需要时按接口校验用户角色或是否拥有某菜单，与前端展示保持一致，实现权限最小化与可维护性。

**系统配置与 AI 助手模块**  
系统配置以键值对形式存储系统名称、时区、第三方 API 密钥等，提供统一的读写接口，供前端“系统设置”或后台使用。AI 助手将会话与消息持久化在会话表与消息表中；在调用大模型前，将当前设备列表、设备健康汇总、各设备 CPU/内存等指标快照拼成上下文注入请求，使问答与当前网络与设备状态相关，提升可用性。

**仪表盘与拓扑、批量命令与网络 AI 命令**  
仪表盘从设备与告警接口获取健康汇总、在线/离线时间线、设备类型分布、告警摘要与最近告警，按分组筛选后展示。设备拓扑从设备列表接口取数据，按类型与状态渲染为节点，支持拖拽布局与状态筛选，布局可持久化到浏览器本地。批量命令对当前筛选或选中的多台设备，按设备厂商选择预置命令（如 display cpu-usage）通过 SSH/Telnet 逐台执行并汇总结果。网络 AI 命令结合大模型与厂商命令模板，根据用户描述推荐或生成命令，仍通过既有 SSH/Telnet 通道执行。这些功能复用设备管理、指标采集与 Web 终端的凭据与连接逻辑，不新增独立数据表。

---

**说明**：本文档包含论文题目、提纲、正文、ER 图（文字版）、功能流程图（文字版）、数据库表关系图（文字版）以及各模块实现说明（含关键模块中**实际用到的**代码片段与简要讲解）。图示部分可直接复制到 draw.io、Visio、PlantUML 等工具中编辑并生成正式插图；模块说明与代码讲解仅选取系统真实使用的逻辑，便于论文“实现”部分与答辩陈述。
