# 基于 Spring Boot 开发的监控运维系统 — 论文正文与 ER/表关系说明

---

# 第一部分：论文正文（超万字）

## 第 1 章 绪论

### 1.1 研究背景与意义

随着企业网络规模不断扩大，路由器、交换机、防火墙以及各类服务器等设备数量激增，传统依赖人工巡检和分散脚本的运维方式已难以满足实时性、可观测性与安全性的要求。本课题基于 Spring Boot 框架开发监控运维系统，旨在实现对异构网络设备的统一纳管、状态监测、指标采集与告警处置，为运维人员提供集中化的可视界面与自动化能力，降低运维成本并提升故障发现与恢复效率。

本系统具有以下现实意义：第一，通过标准化接口（SNMP、SSH/Telnet）对接不同厂商设备，实现多品牌、多类型设备的统一管理；第二，对设备在线状态与 CPU、内存等关键指标进行周期性采集与存储，为容量规划与故障预警提供数据基础；第三，基于规则引擎的告警与可选邮件通知、自动修复机制，能够缩短故障响应时间；第四，结合 Web 终端、配置备份、审计日志与角色权限控制，在提升运维效率的同时保障操作可追溯与权限最小化。

### 1.2 国内外研究现状

当前，商业与开源领域均存在大量网络管理与监控产品。商业产品如 SolarWinds、PRTG、Zabbix 等，功能全面但往往针对通用 IT 监控设计，对国内常见厂商（如华为、华三、锐捷）的深度适配与二次开发灵活性有限；开源方案如 LibreNMS、Observium、Prometheus + Grafana 等，在可扩展性与成本方面具有优势，但在告警联动、Web 终端集成与权限体系上通常需要自行整合。近年来，基于 Spring Boot、Vue 等技术的 B/S 架构运维平台逐渐成为主流，将设备管理、指标展示、告警与用户权限统一在浏览器端完成，便于跨地域与多角色协作。本课题基于 Spring Boot 开发的监控运维系统采用前后端分离的 B/S 架构，在设备管理、多协议采集、告警与权限等方面进行一体化设计，并针对华为、华三、锐捷等设备做了命令与解析适配，具有一定的工程参考价值。

从技术路线看，网络设备监控通常涉及以下几类数据流：一是设备元数据与配置信息，适合用关系型数据库持久化；二是状态与指标的时序数据，适合用时序库（如 InfluxDB）或缓存（如 Redis）存储，以支持高效写入与按时间范围查询；三是告警事件与操作审计，需要与设备、规则、用户等关联，仍以关系库为主。本系统在实现上采用 MySQL 存业务表、Redis 存设备指标缓存、InfluxDB 存设备状态时序与 Telegraf 指标，与上述划分一致。在采集策略上，系统采用“SNMP 优先、SSH/Telnet 回退”的方式，既照顾到主流网络设备的 SNMP 能力，又兼顾仅开放 Telnet 或 SSH 的旧设备与防火墙，避免因协议单一导致部分设备无法纳入监控的问题。

### 1.3 课题主要工作与论文结构

本论文题目为“基于 Spring Boot 开发的监控运维系统”。本课题主要完成以下工作：（1）对网络设备监控与运维管理进行需求分析，明确功能模块与用户角色；（2）设计系统总体架构与数据库模型，包括设备、告警、用户权限、审计、配置备份、AI 会话等实体及关系；（3）实现设备管理、状态监测、指标采集（SNMP/SSH-Telnet）、告警规则与历史、Web SSH/Telnet 终端、配置备份、操作审计、用户与角色及菜单权限、系统配置、AI 助手等核心功能；（4）对关键流程进行说明，并给出 ER 图与数据库表关系的文字化描述，便于论文撰写与答辩展示。

论文其余部分安排如下：第 2 章介绍相关技术；第 3 章进行系统分析；第 4 章阐述系统设计（含总体设计与数据库设计）；第 5 章按功能模块说明实现要点；第 6 章介绍系统测试与运行环境；第 7 章总结全文并展望后续工作。ER 图与数据库表关系以附录形式在第二部分及以后给出。

---

## 第 2 章 相关技术简介

### 2.1 Spring Boot

Spring Boot 是基于 Spring 框架的快速开发脚手架，通过自动配置、起步依赖与内嵌容器，使开发者能够以最少配置构建独立运行的 Java 应用。本系统采用 Spring Boot 作为后端基础：通过 Spring Web 提供 REST 接口与 WebSocket（长连接，用于 Web 终端与后端实时双向通信）支持，通过 Spring Data JPA 与数据库交互，通过 @Scheduled（定时任务注解，指定“每隔多久执行一次”）实现状态检测、指标采集、告警评估，通过条件装配（如 @ConditionalOnProperty，即“配置文件里某个开关为 true 才加载该功能”）按配置启用或禁用 SNMP、SSH 采集等模块。Spring Boot 的依赖注入与分层架构（Controller、Service、Repository）使业务逻辑与数据访问清晰分离，便于维护与扩展。

### 2.2 前端与相关技术

系统前端采用 Vue 3 构建单页应用（SPA，即整站一个页面、通过前端路由切换视图，无需每次整页刷新），通过 Vue Router 管理路由、Axios 发起 HTTP 请求，与后端 REST 接口对接。终端功能使用 xterm.js（在浏览器里画出“黑底白字”的命令行窗口的组件）在浏览器中模拟字符终端，通过 WebSocket（长连接）与后端建立的 SSH/Telnet 会话进行双向数据转发。图表与仪表盘使用 ECharts 展示设备类型分布、在线/离线时间线、告警摘要等。前端与后端分离部署，便于独立开发、构建与按需由 Nginx 托管静态资源。

### 2.3 数据存储与中间件

**MySQL**：存储业务数据，包括设备、告警规则与历史、用户与角色、审计日志、配置备份、AI 会话与消息等。表结构通过 Flyway（按版本号依次执行 SQL 脚本的数据库迁移工具，改表结构可追溯、可重复）进行版本化迁移，与 JPA 实体保持一致。**Redis**：内存型键值库，在本系统中用作设备指标缓存与可选 SNMP 采集结果的短期存储，供实时指标接口快速读取，减轻关系库压力。**InfluxDB**：时序数据库（按“时间 + 指标名”高效写入和按时间范围查询），用于存储设备状态（在线/离线、RTT）及由 Telegraf 写入的 Linux 服务器 CPU、内存等指标，支持按时间范围聚合与仪表盘时间线展示。**RTT**（Round-Trip Time，往返时延）：本系统中指从本机发探测包到设备再收到回应所经历的毫秒数，用来判断网络是否通畅、延迟是否偏大。三者分工明确：关系数据入 MySQL，时序与高写场景用 InfluxDB，热点指标用 Redis。

### 2.4 网络与协议

**SNMP**（Simple Network Management Protocol，简单网络管理协议）：广泛用于路由器、交换机等网络设备的监控，系统通过 SNMP 向设备查询“某个 OID（对象标识符，可理解为设备上某一项数据的编号）对应的值”，从而拿到 CPU、内存等指标。支持 v2c（用团体名作为简单口令）与 v3（带用户名、认证与加密，更安全）。**SSH**：用于安全登录与远程命令执行。本系统使用 JSch（Java 里常用的 SSH 客户端库）建立 SSH 会话与 Shell 通道，实现 Web 终端与设备 SSH 登录，并在 SNMP 不可用时通过 SSH 在设备上执行厂商命令（如华为/华三的 display cpu-usage，即“查 CPU 使用率”的命令）进行指标采集。**Telnet**：一种较老的远程登录协议，部分老旧或封闭设备只开放 Telnet。系统使用 Apache Commons Net 的 TelnetClient 连接设备，自动识别设备弹出的“用户名”“密码”提示并发送凭据完成登录，并支持保活报文（见下文 Web 终端保活）以减少空闲断开。采集策略上采用“SNMP 优先、SSH/Telnet 回退”，兼顾多厂商与多协议环境。

---

## 第 3 章 系统分析

### 3.1 业务需求概述

系统面向的典型用户包括：网络管理员、运维人员与只读查看人员。管理员需要完成设备增删改查、分组与标签管理、SNMP/SSH 等接入参数的配置；运维人员需要查看设备状态与 CPU/内存等指标、接收告警、通过 Web 终端登录设备执行命令，并可能执行批量命令与配置备份；只读用户仅需查看仪表盘、设备列表与告警信息，不进行修改与终端操作。此外，系统需支持按分组筛选设备、按角色控制菜单与敏感信息（如密码）的可见性，并对关键操作记录审计日志。

从业务场景上看，网络规模扩大后设备类型多样：既有运行 Linux 的服务器，也有华为、华三、锐捷、思科等厂商的路由器、交换机与防火墙。这些设备的管理方式不一：服务器通常通过 SSH 与 Telegraf 采集指标；网络设备则多支持 SNMP，部分老旧或封闭设备仅开放 Telnet。因此系统需支持多协议、多厂商的统一纳管，并在 SNMP 不可用或未配置时能够回退到 SSH/Telnet 采集，避免“看不到”已添加设备的数据。同时，设备在线状态的判定不能仅依赖 ICMP（即常说的“Ping”，不少环境禁 ping 或 Windows 上不可靠），需对网络设备增加 TCP 端口探测：161 为 SNMP 端口、22 为 SSH 端口、23 为 Telnet 端口，任一端口能连通即视为在线，以保证列表与 Ping 结果一致。在交互层面，用户希望添加或编辑设备时管理 IP 必须符合 IPv4 规范才能保存，避免错误数据入库；设备列表在数据量较大时需分页展示（如每页 10 条），以提升加载与操作效率。

### 3.2 功能需求

**设备管理**：支持设备的添加、编辑、逻辑删除与列表展示；设备属性包括名称、管理 IP（须符合 IPv4 规范）、类型（服务器、路由器、交换机、防火墙、其他）、厂商与型号、分组、SSH/Telnet 端口与凭据、SNMP 版本与团体名或 v3 安全配置等；支持按分组筛选与分页展示（如每页 10 条）；支持单设备 Ping 检测，并将检测结果回写设备状态与最后轮询时间，使列表与终端展示的在线/离线状态一致。

**状态监测**：对未删除设备进行周期性的可达性检测（先 ICMP 即 Ping；若失败则对网络设备再试 TCP 端口 161/22/23，即 SNMP/SSH/Telnet 端口，任一连通即判在线），更新设备状态（正常、告警、严重、离线）与最后轮询时间，并将状态时序写入 InfluxDB（若已配置），供仪表盘时间线等使用；状态变化时可触发告警规则。

**指标采集**：Linux 服务器通过 Telegraf（部署在服务器上的采集程序，把本机 CPU、内存等写入 InfluxDB）将指标写入 InfluxDB，或由系统通过 SSH 回退采集；网络设备优先通过 SNMP 采集 CPU、内存，若未配置 SNMP 或 SNMP 采集失败，则回退至 SSH/Telnet 在设备上执行厂商命令（如 display cpu-usage、display memory-usage，即“查 CPU/内存使用率”）并从输出中解析出百分比。采集结果写入**内存缓存**（本进程内，用于当前请求或刚采完立刻被读）与 **Redis**（用于持久一段时间、供“设备指标”页等接口按 IP 或设备读取）；**实时指标接口**即前端“设备指标”页调用的接口，负责把上述来源与 InfluxDB 的 Linux 指标汇总后返回。支持“刷新”触发一次采集，定时采集间隔可配置（如 5 分钟）。

**告警管理**：支持创建告警规则，按设备或设备类型、指标键（如 CPU、内存）、条件与严重程度进行配置，并可启用邮件通知与自动修复（如 SSH 命令或本地脚本）；系统定期根据当前指标与设备状态评估规则，产生告警历史（触发/恢复），并支持按规则、设备、时间等查询与导出。

**Web 终端**：支持通过浏览器建立与设备的 SSH 或 Telnet 会话（端口 22/23），使用设备管理中配置的用户名与密码进行登录；对 Telnet 支持自动识别登录与密码提示并发送凭据；支持 SSH/Telnet 保活，减少设备侧空闲断开会话；终端输出可被解析并写入设备指标缓存，便于设备指标页展示。

**配置备份与审计**：支持对设备列表、告警规则、系统配置等进行备份与恢复，备份记录存储于数据库；对设备与告警等关键操作记录审计日志（操作人、动作、对象、详情、IP、时间），支持按条件查询。

**用户与权限**：用户与角色多对多关联；角色可配置可分配菜单（角色-菜单模板）；用户可见菜单为所属角色可分配菜单的并集；敏感字段（如设备 SSH/SNMP 密码）仅对管理员角色可见与可编辑。

**系统配置与 AI 助手**：系统配置表存储键值对（如系统名称、API 密钥等）；AI 助手支持会话与消息持久化，并可结合设备列表、健康汇总与指标快照等上下文进行问答。

除上述功能外，系统还需提供仪表盘（设备健康汇总、在线/离线时间线、设备类型分布、告警摘要）、设备拓扑视图（以图形化展示设备及其连接关系，支持按状态筛选）、批量命令执行（对多台设备下发相同或按厂商区分的命令）、以及基于大模型的网络 AI 命令推荐与问答。这些功能与设备管理、指标采集、告警与权限紧密配合，共同构成完整的运维管理闭环。

### 3.3 非功能需求

系统应具备较好的可用性与可维护性：关键采集与检测任务采用定时调度与异步执行，避免阻塞主流程；前后端分离便于独立部署与扩展；数据库采用 Flyway 进行版本化迁移，表结构与实体保持一致。安全性方面：用户密码加密存储、接口按角色与菜单权限控制、敏感信息脱敏展示、操作审计留痕。性能方面：设备列表分页、指标与告警数据按需查询与聚合，InfluxDB 与 Redis 用于时序与缓存以减轻关系库压力。

---

## 第 4 章 系统设计

### 4.1 系统架构

系统采用前后端分离的 B/S 架构。前端为基于 Vue 3 的单页应用，负责页面渲染、路由、表单校验与用户交互，通过 HTTP 与 WebSocket 与后端通信；后端基于 Spring Boot 提供 REST 接口与 WebSocket 服务，负责业务逻辑、数据持久化、与设备及外部组件（InfluxDB、Redis、邮件、大模型 API）的交互。设备状态监测、SNMP 采集、SSH/Telnet 采集等由 Spring 定时任务或异步任务触发；告警评估由定时任务根据当前指标与规则写入告警历史；Web 终端通过 WebSocket 维持长连接，后端将 SSH/Telnet 会话的输入输出与浏览器双向转发。

### 4.2 技术选型

后端：Java 21、Spring Boot、Spring Data JPA、Flyway（数据库表结构版本迁移）、JSch（Java 的 SSH 客户端库）、Apache Commons Net（Telnet 客户端）、Snmp4j 或等效 SNMP 库、InfluxDB 客户端、Redis、Lombok 等。前端：Vue 3、Vue Router、Axios、xterm.js（终端）、ECharts（图表）等。数据库：MySQL 存储业务数据；Redis 存储设备指标缓存与可选 SNMP 结果；InfluxDB 存储设备状态时序与可选 Telegraf 指标。部署与运行：后端可打包为可执行 JAR，前端构建为静态资源由后端或 Nginx 提供；支持通过配置文件或环境变量配置数据库、Redis、InfluxDB、邮件、采集间隔等。

### 4.3 功能模块划分

根据需求，系统划分为以下功能模块：设备管理、状态监测、指标采集（含 SNMP 与 SSH/Telnet）、告警管理、Web 终端、配置备份、操作审计、用户与角色及菜单权限、系统配置、AI 助手。仪表盘、设备拓扑、批量命令、网络 AI 命令等作为设备与监控的延伸展示与操作入口，归入相应模块或单独作为子模块。各模块共享设备、用户、角色等基础数据，通过服务层与控制器组织接口与权限。

前端菜单编码与后端 MenuConstants 保持一致，包括：dashboard（仪表盘）、devices（设备列表）、topology（拓扑）、batch-command（批量命令）、network-ai-command（网络 AI 命令）、alerts（告警）、metrics（设备指标）、ai-assistant（AI 助手）、audit（操作日志）、backup（配置备份）、users（用户管理）、system（系统设置）。角色可分配菜单存储在 role_menu 表中，用户登录后可见菜单为其所属角色可分配菜单的并集，从而实现按角色控制功能入口与数据可见范围。

### 4.4 Spring Boot 在系统中的应用

本系统基于 Spring Boot 构建，充分利用其核心特性以提升开发效率与运行可维护性。（1）**自动配置**：数据源、JPA、Redis、Flyway 等通过引入对应 Starter 与配置文件即可完成集成，无需手写大量 XML 或 Java 配置。（2）**依赖注入与分层**：Controller、Service、Repository 分层清晰，通过 @RestController、@Service、@Repository 等注解声明 Bean，由 Spring 容器统一管理生命周期与依赖关系。（3）**定时任务**：设备状态检测、SNMP/SSH 采集、告警评估等通过 @Scheduled（定时任务注解，可指定“上次执行完再过多久执行下一次”或 cron 表达式）声明周期与间隔，与业务 Service 解耦，便于调整与监控。（4）**条件装配**：部分功能（如 SNMP 采集、SSH 采集）通过 @ConditionalOnProperty（即“配置文件里某开关为 true 才加载该 Bean”）控制是否加载，便于通过配置开关启用或禁用模块。（5）**WebSocket**：Web 终端采用 Spring WebSocket 与 AbstractWebSocketHandler，与 REST 接口共用同一应用，便于会话管理与权限统一。（6）**事务与异常**：关键写操作使用 @Transactional（事务注解，即“这一段里的多次数据库写要么全成功要么全回滚”），保证数据一致性；全局异常与校验可与 Spring MVC 结合，统一返回格式与错误信息。上述应用体现了“基于 Spring Boot 开发”在监控运维系统中的作用：快速集成、规范分层、可配置、易扩展。

### 4.5 部署与运行模型

后端以 Spring Boot 可执行 JAR 形式运行，内嵌 Tomcat 提供 REST 与 WebSocket；前端构建后的静态资源可置于 JAR 同目录或由 Nginx 反向代理。数据库连接、Redis、InfluxDB、邮件服务器、SNMP/SSH 采集开关与间隔等均通过 application.yml 或环境变量配置，便于开发、测试与生产环境分离。定时任务（设备状态检测、SNMP 采集、SSH 采集、告警评估、Web SSH 探活等）的间隔与超时均可调，在保证数据新鲜度的同时避免对设备与网络造成过大压力。

### 4.6 数据库设计原则

数据库设计遵循以下原则：实体与表一一对应，主键统一为自增 BIGINT；枚举型字段使用 ENUM 或 VARCHAR 与后端枚举转换器对应；时间字段统一为 DATETIME，创建与更新时间由应用或默认值维护；**逻辑删除**采用 deleted 标记（即把记录标为“已删除”而不真正从库中删掉，用途是保留历史关联、避免外键报错）；多对多关系通过中间表（如 user_role、role_menu）维护；外键在迁移中按需保留或删除以兼顾性能与约束一致性；表结构与 Flyway 迁移脚本及 JPA 实体保持一致，便于版本管理与对比。

### 4.7 核心表概览

**设备与监控相关**：device（设备主表，含 IP、类型、厂商、SNMP/SSH 配置、状态、最后轮询时间等）；设备状态时序写入 InfluxDB，不在此关系库中建表。

**告警相关**：alert_rule（告警规则）、alert_history（告警历史）、alert_template（告警模板，可选）。

**用户与权限**：sys_user（用户）、role（角色）、user_role（用户-角色）、role_menu（角色可分配菜单）。

**审计与配置**：audit_log（审计日志）、system_config（系统配置）、config_backup（配置备份）。

**AI**：ai_chat_session（AI 会话）、ai_chat_message（AI 消息）。

上述表结构及列说明见项目内《后端与数据库表结构对照》文档；迁移变更（如已删除的 user_menu、monitor_item 等）以 Flyway 脚本为准。

### 4.8 表间关系概要

device 与 alert_rule、alert_history 通过 device_id 关联（规则可指定设备或按类型匹配，历史记录设备与规则）；alert_history 与 alert_rule 通过 rule_id 关联。sys_user 与 role 通过 user_role 多对多关联；role 与菜单通过 role_menu 关联（存储菜单编码）。audit_log、config_backup 通过 username 或 user_id 与用户概念关联，可不建外键。ai_chat_message 通过 session_id 与 ai_chat_session 关联。device 为独立核心实体，与告警、采集、Web 终端等通过业务逻辑与 IP/ID 关联，不强制外键。详细关系见下文“数据库表关系图（文字描述）”一节。

### 4.9 主要表字段说明（选列）

**device**：id（主键）、name（设备名称）、ip（管理 IP）、type（router/switch/server/firewall/other）、vendor、model、snmp_version、snmp_community、snmp_security、snmp_port、ssh_port、ssh_user、ssh_password、group_name、status（normal/warning/critical/offline）、last_poll_time、create_time、update_time、deleted（逻辑删除）。

**alert_rule**：id、name、device_id（可选）、device_types、metric_key、rule_condition、severity、enabled、notify_email、auto_fix_enabled、auto_fix_type、auto_fix_command、create_time、update_time。

**alert_history**：id、rule_id、device_id、metric_key、trigger_value、start_time、end_time、status（firing/resolved）、severity、message、create_time、update_time。

**sys_user**：id、username（唯一）、password、email、enabled、create_time、update_time。

**role**：id、code（如 ADMIN、OPERATOR）、name、description、create_time、update_time。

**user_role**：user_id、role_id（联合主键，多对多）。

**role_menu**：id、role_id、menu_code（联合唯一，角色可分配菜单）。

**audit_log**：id、username、action、target_type、target_id、detail、ip、create_time。

**config_backup**：id、name、backup_type、summary、content（LONGTEXT JSON）、user_id、create_time。

**ai_chat_session**：id、username、title、create_time。**ai_chat_message**：id、session_id、role（user/assistant）、content、create_time。

### 4.10 迁移与版本管理

表结构变更通过 Flyway 迁移脚本管理，按版本号顺序执行（如 V2 建 system_config、config_backup，V11 建 role_menu，V14 删除 user_menu 等）。实体与表结构对照文档（如《后端与数据库表结构对照》）列出各表列名、类型与注释，并注明已删除的列或表，便于与现有库对比修补。采用逻辑删除（device.deleted，即“软删除”，不真删记录只打标记）时，查询均过滤已删除记录，避免统计到已删设备与外键问题。

---

## 第 5 章 系统功能模块实现说明

### 5.1 设备管理

**DeviceController**（设备控制器，用途是接收前端的设备增删改查与 Ping 等请求）与 **DeviceService**（设备业务层，用途是执行具体的保存、查询、调用可达性检测等逻辑）完成设备 CRUD；列表支持按分组筛选与**分页**（如每页 10 条，即一次只加载一页数据，减轻前端压力），前端对排序后列表按页码切片展示。新增与编辑时对管理 IP 做 IPv4 格式校验（四段 0～255），不通过则不提交并提示错误；保存时对 SSH 密码等敏感字段在非管理员留空时保留原值，避免误清空。单设备 **Ping 接口**（用途是让用户点击“Ping”后立即得到该设备是否在线及 RTT）：调用可达性检测并更新设备状态与 last_poll_time（最后轮询时间），前端刷新列表后即可看到与 Ping 结果一致的状态。

### 5.2 状态监测

**MonitorService**（状态监测服务，用途是周期判断每台设备是否在线、并更新设备表里的状态与最后轮询时间）定时执行**可达性检测**（即“设备能不能连上”：先 Ping，失败再试 TCP 端口）。先 ICMP（即 Ping），失败则对非 server 类型设备依次尝试 TCP 161（SNMP 端口）、设备 SSH/Telnet 端口、23（Telnet 端口）；根据 RTT（往返时延，单位毫秒）或连接结果更新设备状态与 last_poll_time 并落库，状态变化时**触发告警回调**（即调用告警服务，用于产生“设备上线/下线”类告警）；可选将状态时序写入 InfluxDB（供仪表盘时间线等按时间查询）。采集间隔与超时可通过配置项调整（如 5 分钟一次）。

### 5.3 指标采集

**DeviceSnmpCollectService**（SNMP 采集服务）：负责向已配置 SNMP 的网络设备发 SNMP 请求，取回 CPU、内存等指标。若某台设备 SNMP 采集失败且该设备已配置 SSH/Telnet，则**回退**至 **DeviceSshCollectService** 对该设备做一次 SSH/Telnet 采集（即“SNMP 不行就改用登录设备执行命令的方式取指标”）。若系统里没有任何设备配置 SNMP，定时任务仍会主动调用 SSH 采集，目的是让“只配了 SSH、没配 SNMP”的在线网络设备也能在指标页看到数据。**DeviceSshCollectService**（SSH/Telnet 采集服务）：通过 SSH 或 Telnet 登录设备，按厂商（华为、华三、锐捷、思科）选择对应的查询命令（如 display cpu-usage、show processes cpu），从命令输出里解析出 CPU、内存百分比，写入**内存缓存**（本进程内，供当前请求快速读）与 **Redis**（供多实例或下次请求读，也是“设备指标”页网络设备数据的来源之一）。**实时指标接口**（供前端“设备指标”页调用的接口）：把各来源的数据汇总后返回——优先从 SSH 刚采到的内存缓存按 IP 取网络设备指标，再按设备从 Redis 取；Linux 服务器的指标则从 Telegraf 写入的 InfluxDB 里查。用户点**刷新**时：触发一次采集（Linux 侧同步查 InfluxDB，网络设备侧触发异步采集），接口先返回当前已有数据，新采到的数据下次刷新或打开页面时可见。

### 5.4 告警管理

**告警规则**（即“什么情况下算告警”，如某设备 CPU>80 或设备离线）支持按设备、设备类型、指标键、条件与严重程度配置，并可启用邮件通知与自动修复。**AlertService**（告警服务，用途是根据规则与当前设备状态/指标产生告警记录、并可选发邮件或执行自动修复）在设备状态变化时写入设备上下线类告警；定时任务根据当前指标与规则进行**阈值判断**（如 CPU 是否大于规则里设的 80），满足则写入或保持“触发中”的告警历史，不满足则若此前有触发中则更新为“已恢复”。告警历史支持按规则、设备、时间等查询与导出；邮件发送由 **AlertEmailService**（告警邮件服务，用途是在规则触发时异步发邮件）在规则触发时调用，依赖系统配置中的邮件参数。

### 5.5 Web 终端

**WebSshService**（Web 终端连接服务，用途是根据设备信息建立 SSH 或 Telnet 会话，供用户在浏览器里操作设备）根据设备端口 22/23 建立 SSH 或 Telnet 会话，Telnet 自动发送用户名与密码完成登录；SSH 侧配置保活，即每隔一段时间向设备发一次“我还连着”的小包（对应参数 ServerAliveInterval，如 45 秒一次），设备若长时间收不到这类包会认为用户已离开而主动断开连接，定时发保活可避免用户只是暂时没敲命令就被踢下线；另一参数 ServerAliveCountMax 表示连续多少次收不到设备回应才判定为真断开。Telnet 侧则定时发送保活报文（IAC NOP，即 Telnet 协议里的“空操作”包，相当于告诉设备“会话还在用、别断”），同样是为了减少设备因空闲而断开会话。**SshWebSocketHandler**（WebSocket 处理器，用途是维护“浏览器 WebSocket 连接”与“后端 SSH/Telnet 会话”的对应关系）：将用户在前端输入的字符转发至设备、把设备输出转发至前端；并可对终端输出进行解析，把解析出的 CPU、内存等写入**设备指标缓存**（供实时指标接口或设备指标页使用，这样用户在终端里执行了查 CPU 的命令后，指标页也能看到最新数据）。连接失败时仅对“连接/网络”类失败标记该设备为“曾连不上”（用于后续 SNMP 失败时是否尝试 SSH 回退采集等判断），未配置凭据不标记，避免误伤。

### 5.6 配置备份与审计

**ConfigBackupService**（配置备份服务，用途是“一键备份/恢复”：把当前设备列表、告警规则、系统配置等打成一份快照）将设备、告警规则、系统配置等序列化为 JSON 存储于 **config_backup**（配置备份表）；恢复时按类型解析并写回相应表。**AuditService**（审计服务，用途是记录“谁在什么时候对什么做了啥”，便于事后追溯）在设备与告警等关键操作后写入 **audit_log**（审计日志表，存操作人、动作、对象类型与 ID、详情、IP、时间）；查询接口支持按动作、对象、时间等筛选。

### 5.7 用户与权限

用户与角色通过 **user_role**（用户-角色中间表，用途是实现“一个用户可有多个角色”）多对多关联；角色可分配菜单存储在 **role_menu**（角色-菜单表，存的是“该角色能访问哪些菜单”，菜单编码与前端路由一致，用途是控制用户登录后能看到哪些菜单）。登录后返回用户信息及角色编码列表；前端根据后端下发的菜单列表**渲染侧栏与路由**（即左侧菜单栏显示什么、能点进哪些页面）；设备密码等敏感字段仅对 **ADMIN**（管理员角色）展示与可编辑。后端接口按操作需要校验角色或菜单权限，与前端展示一致。

### 5.8 系统配置与 AI 助手

**SystemConfig**（系统配置，以键值对形式存储系统名称、时区、邮件参数、大模型 API 密钥等，用途是供各模块统一读取配置）；前端或后台管理可读写（需权限）。AI 会话与消息持久化在 ai_chat_session、ai_chat_message；**AiChatService**（AI 助手服务，用途是接收用户提问、调用大模型并返回回答）在调用大模型前注入设备列表、健康汇总与设备指标快照等**上下文**（即把这些信息一并发给大模型，让回答能结合当前设备与监控状态），提升问答相关性。

### 5.9 仪表盘与拓扑

**仪表盘**（用途是给用户一个总览：当前有多少设备正常/告警/离线、最近 24 小时上下线情况、设备类型分布、最近告警）聚合设备健康汇总（正常、告警、严重、离线数量）、最近 24 小时在线/离线时间线（来自 InfluxDB 或后端聚合）、设备类型饼图、告警摘要与最近告警列表；支持按分组筛选。**设备拓扑页**（用途是以图形方式展示设备及其关系，便于一眼看到网络结构）从设备列表读取数据，按类型与状态渲染节点，支持拖拽布局与状态筛选；拓扑位置可持久化到前端存储（如 localStorage），便于用户自定义视图。两处均依赖设备与告警数据接口，不新增独立表。

### 5.10 批量命令与网络 AI 命令

**批量命令模块**（用途是对多台设备一次性下发相同或按厂商区分的命令，并汇总结果）对当前筛选出的多台设备（或选中的设备）按设备类型或厂商选择**命令模板**（如华为的 display cpu-usage，即“查 CPU 使用率”），通过 **BatchCommandService**（批量命令服务，用途是逐台建立 SSH/Telnet、执行命令、收集输出并返回）依次执行 SSH/Telnet 并汇总结果。**网络 AI 命令模块**（用途是根据用户自然语言描述，由大模型推荐或生成设备命令）结合大模型与预置的厂商命令模板，根据用户描述推荐或生成命令，仍通过既有 SSH/Telnet 通道执行。两者复用设备管理与 SSH/Telnet 采集的凭据与连接逻辑，不单独建表。

### 5.11 关键业务流程简述

**用户登录与权限**（用途：验证身份并确定用户能访问哪些菜单）：用户提交用户名与密码，后端校验通过后生成或返回会话信息，并查询该用户关联角色（user_role）及角色可分配菜单（role_menu），将菜单编码列表返回前端；前端据此渲染侧栏与路由（即显示哪些菜单、能进哪些页面），后续请求可携带 Token 或 Cookie，后端按需校验角色或菜单权限。**设备添加与状态更新**（用途：把设备信息入库，并周期或按需更新其在线状态）：用户填写设备表单（含 IP、类型、SSH/SNMP 等），前端校验 IP 为 IPv4 后提交；后端（DeviceController/DeviceService）保存至 device 表，新设备默认状态为离线；定时监测任务（MonitorService）或用户点击 Ping 时执行可达性检测（先 Ping，失败再试 TCP 161/22/23），将结果写回 device.status 与 last_poll_time。**指标采集与展示**：定时或“刷新”触发 SNMP/SSH 采集（由 DeviceSnmpCollectService 与 DeviceSshCollectService 完成，用途是拿到各设备的 CPU、内存等供页面展示与告警用），网络设备结果写入内存缓存与 Redis；**实时指标接口**（给“设备指标”页用的接口）汇总 Telegraf/InfluxDB 的 Linux 指标与按 IP/设备从缓存或 Redis 取出的网络设备指标，返回前端；设备指标页按类型展示 Linux 设备与网络设备（路由器/交换机/防火墙），离线设备可显示为“—”或带离线标记。**告警触发与恢复**（用途：在设备上下线或指标超阈值时产生告警记录并可发邮件）：设备状态变化时 AlertService 写入设备上下线告警；定时任务读取当前设备指标与规则条件，若满足则插入 alert_history（status=firing，即“告警触发中”），恢复后更新 end_time 与 status=resolved（已恢复）；若规则启用邮件通知，则调用 AlertEmailService 发送通知。

---

## 第 6 章 系统测试与运行环境

### 6.1 测试思路

系统测试可从以下几方面进行。（1）**功能测试**：对设备 CRUD、分组筛选、分页、Ping 及状态回写、告警规则与历史、Web 终端、配置备份、用户与角色及菜单、审计日志等逐项验证，确认符合需求说明。（2）**接口测试**：对主要 REST 接口与 WebSocket 连接进行请求与响应校验，包括参数校验、权限控制与异常返回。（3）**集成验证**：在配置 MySQL、Redis 及可选 InfluxDB 与邮件后，运行定时任务与采集流程，确认设备状态与指标能正确更新并展示；验证 SNMP 失败时 SSH 回退与无 SNMP 设备时 SSH 采集触发。（4）**兼容性**：在不同浏览器与分辨率下检查前端展示与操作；对华为、华三、锐捷等设备实际接入，验证命令解析与指标展示。测试过程中发现的问题（如 Ping 不回写状态、终端空闲断开等）已在实现中通过状态回写、保活与类型过滤等方案予以改进。

### 6.2 运行环境

**开发与运行**：JDK 21、Maven、Node.js（前端构建）；MySQL 5.7 或 8.x、Redis（可选）、InfluxDB（可选）。**配置**：application.yml 中配置数据源、Redis 连接、InfluxDB 与邮件等；采集间隔、超时等可通过同一配置文件或环境变量覆盖。**部署**：后端打包为 spring-boot-maven-plugin 生成的可执行 JAR，前端 npm run build 后可将 dist 内容拷贝至后端静态资源目录或由 Nginx 托管；单机即可运行，如需高可用可多实例部署并共享 MySQL/Redis。上述内容可作为论文中“系统实现与运行环境”的补充。

---

---

## 第 7 章 总结与展望

本课题基于 Spring Boot 设计并实现了一个面向网络设备监控与运维管理的 B/S 系统，完成了设备管理、状态监测、多协议指标采集、告警规则与历史、Web SSH/Telnet 终端、配置备份、审计、用户与角色及菜单权限、系统配置与 AI 助手等核心功能，并对华为、华三、锐捷等厂商设备做了命令与解析适配。数据库采用 MySQL 存储业务数据，通过 Flyway 管理表结构迁移，与 JPA 实体保持一致；ER 与表关系已以文字形式整理，便于论文撰写与答辩展示。

在实现过程中，针对“设备列表 Ping 成功但仍显示离线”“无 SNMP 设备时仍希望对在线设备进行 SSH 采集”“Web 终端空闲断开会话”“设备指标页看不到新添加的华为/华三设备”等实际问题，通过将 Ping 结果回写设备状态、在无 SNMP 设备时主动触发 SSH 采集、为 SSH/Telnet 增加保活、以及明确设备类型须为路由器/交换机/防火墙才能在网络设备列表中展示等设计，提升了系统的可用性与可理解性。设备管理表单中增加 IPv4 格式校验与分页展示，进一步保证了数据规范与交互效率。

后续可在以下方面继续完善：增加更多厂商与指标类型的适配、告警渠道（如企业微信、钉钉）、更丰富的仪表盘与报表、以及性能与安全方面的专项优化与测试；同时可对 ER 与表关系的文字描述进行图形化绘制，形成规范的数据库设计图与业务流程图，便于归档与团队协作。

---

# 第二部分：总体 ER 图流程（文字描述）

以下用文字描述系统总体 ER 流程，便于绘制总体 ER 图或流程图。

**实体与参与关系**  
- **设备（device）**：核心实体，包含名称、IP、类型、厂商、SNMP/SSH 配置、状态、分组等；与“告警规则”“告警历史”通过设备 ID 产生关联（规则可指定设备或按类型匹配，历史记录对应设备与规则）。  
- **告警规则（alert_rule）**：可关联设备（device_id 可选）、设备类型、指标键、条件、严重程度等；与“告警历史”为一对多（一条规则可产生多条历史）。  
- **告警历史（alert_history）**：关联 rule_id、device_id，记录触发值、开始/结束时间、状态（触发中/已恢复）、严重程度与消息。  
- **用户（sys_user）**：与“角色（role）”通过“用户-角色（user_role）”多对多关联。  
- **角色（role）**：与“菜单”概念通过“角色-菜单（role_menu）”关联（菜单以编码存储，非独立表）。  
- **审计日志（audit_log）**：记录操作人、动作、对象类型与 ID、详情、IP、时间；逻辑上关联“用户”与操作对象（设备、告警等），可不建外键。  
- **系统配置（system_config）**：键值对，独立实体。  
- **配置备份（config_backup）**：记录备份名称、类型、内容 JSON、操作用户 ID、时间；逻辑上关联用户。  
- **AI 会话（ai_chat_session）**：关联用户名；与“AI 消息（ai_chat_message）”为一对多。  
- **AI 消息（ai_chat_message）**：关联 session_id、角色（user/assistant）、内容、时间。  
- **告警模板（alert_template）**：可选实体，与规则结构类似，用于快速创建规则。

**总体流程（可画为数据流或 ER 图）**  
1) 用户通过角色获得菜单与权限，登录后访问各功能。  
2) 设备由管理员维护，状态由监测任务更新，指标由 SNMP/SSH 采集写入缓存或 Redis。  
3) 告警规则针对设备或类型与指标配置，评估后产生告警历史；状态变化也可产生设备上下线告警。  
4) 关键操作写入审计日志；配置备份将设备与配置快照存入 config_backup。  
5) AI 会话与消息独立存储，调用外部 API 时注入设备与指标等上下文。  
6) 设备状态时序可选写入 InfluxDB，与 MySQL 业务表无直接 ER 连线，仅在业务逻辑中按 device_id 关联。

**业务/数据流图（文字描述，便于画流程图）**  
- **设备状态检测流程**（用途：周期判断每台设备是否在线，更新设备表状态并触发上下线告警）：定时触发（MonitorService）→ 读取未删除设备列表 → 对每台设备先 ICMP（Ping），失败则对非 server 设备依次尝试 TCP 161（SNMP）、ssh_port、23（Telnet）→ 根据结果更新 device.status、last_poll_time 并保存 → 若状态变化则调用告警回调（AlertService.onDeviceStatusChange）→ 可选写 InfluxDB（供仪表盘时间线等使用）。  
- **指标采集与展示流程**：定时或“刷新”触发 → 网络设备：有 SNMP 则先 SNMP 采集（DeviceSnmpCollectService，用途是向设备要 CPU/内存等 OID 数据），失败或无 SNMP 则 SSH/Telnet 采集（DeviceSshCollectService，用途是登录设备执行 display/show 命令并解析输出）；Linux 设备：从 InfluxDB 或 SSH 回退取指标 → 网络设备结果写内存缓存（供刚采完即读）与 Redis（供设备指标页等按 IP 读取）→ **实时指标接口**（供前端“设备指标”页调用）按设备汇总 Telegraf 与 SNMP/SSH 来源数据 → 返回前端展示。  
- **告警评估流程**：定时触发 → 读取启用规则与当前设备指标/状态 → 对每条规则按设备或类型匹配设备，判断条件是否满足 → 满足则写入或更新 alert_history（firing），不满足则若原为 firing 则写恢复（resolved）；设备上下线由状态监测回调直接写告警历史 → 若规则启用邮件则发送通知。

---

# 第三部分：按功能模块的 ER 图（文字描述）

以下按功能模块分别给出 ER 描述，便于分模块绘制 ER 图。

## 3.1 设备管理模块 ER

- **实体**：device（设备）。  
- **属性**：id, name, ip, type, vendor, model, snmp_version, snmp_community, snmp_security, snmp_port, ssh_port, ssh_user, ssh_password, remark, group_name, status, last_poll_time, create_time, update_time, deleted。  
- **关系**：设备为独立实体；在“告警规则/告警历史”中通过 device_id 被引用；在“配置备份”的 content JSON 中可包含设备列表。  
- **可画**：一个矩形表示 device，与 alert_rule、alert_history 之间画“被引用”箭头（1 对多），并注明“device_id”。

## 3.2 告警模块 ER

- **实体**：alert_rule（告警规则）、alert_history（告警历史）、alert_template（告警模板，可选）。  
- **关系**：alert_rule 可关联 device（device_id 可选）、含 device_types、metric_key、condition、severity 等；alert_history 多对一 alert_rule（rule_id）、多对一 device（device_id）；alert_template 与 alert_rule 结构类似，无直接外键，可作为“模板→规则”的创建来源在流程图中体现。  
- **可画**：alert_rule、alert_history、device 三个实体；alert_history 到 alert_rule 与 device 各画一条“多对一”连线，并标 rule_id、device_id。

## 3.3 用户与权限模块 ER

- **实体**：sys_user（用户）、role（角色）、user_role（用户-角色）、role_menu（角色-菜单）。  
- **关系**：sys_user 与 role 通过 user_role 多对多（user_id, role_id）；role 与“菜单”通过 role_menu 关联（role_id, menu_code），菜单为编码集合无独立表。  
- **可画**：sys_user、role、user_role 三个矩形；user_role 与 sys_user、role 各画双线或“多对多”连线；role_menu 与 role 画“多对一”，另一侧标注“menu_code 集合”。

## 3.4 审计与配置备份模块 ER

- **实体**：audit_log（审计日志）、config_backup（配置备份）、system_config（系统配置）。  
- **关系**：audit_log 存 username、target_type、target_id，逻辑上关联用户与各业务对象，可不画外键；config_backup 含 user_id，逻辑上关联用户；system_config 独立键值对。  
- **可画**：三个独立实体；config_backup 可画一条虚线指向“用户”并标 user_id；audit_log 可标注“逻辑关联 username、target_type、target_id”。

## 3.5 AI 助手模块 ER

- **实体**：ai_chat_session（AI 会话）、ai_chat_message（AI 消息）。  
- **关系**：ai_chat_message 多对一 ai_chat_session（session_id）；session 存 username，逻辑关联用户。  
- **可画**：ai_chat_session 与 ai_chat_message 两个实体，message 到 session 画“多对一”并标 session_id。

## 3.6 设备监测与指标（逻辑 ER，无独立表）

- **说明**：设备状态时序在 InfluxDB，设备指标缓存在 Redis 或内存；MySQL 中仅有 device 表。  
- **可画**：在“设备管理模块 ER”中补充说明：device 的 id/ip 在业务逻辑中与 InfluxDB 的 device_status、Redis 的 netpulse:snmp:device:ip:* 对应，可用虚线框标注“外部存储：InfluxDB/Redis”。

---

# 第四部分：数据库表关系图（文字描述）

以下用文字描述各表之间的主键、外键及引用关系，便于绘制“数据库表关系图”。

## 4.1 表清单与主键

- **device**：主键 id。  
- **alert_rule**：主键 id；可选外键/引用 device_id → device.id。  
- **alert_history**：主键 id；rule_id → alert_rule.id；device_id → device.id（若建外键）。  
- **alert_template**：主键 id；无外键。  
- **sys_user**：主键 id。  
- **role**：主键 id。  
- **user_role**：联合主键 (user_id, role_id)；user_id → sys_user.id；role_id → role.id。  
- **role_menu**：主键 id；唯一 (role_id, menu_code)；role_id → role.id。  
- **audit_log**：主键 id；无外键（username 为字符串，target_type/target_id 为逻辑关联）。  
- **system_config**：主键 id；唯一 config_key。  
- **config_backup**：主键 id；user_id 可关联 sys_user.id（可选外键）。  
- **ai_chat_session**：主键 id；无外键（username 为字符串）。  
- **ai_chat_message**：主键 id；session_id → ai_chat_session.id。

## 4.2 关系矩阵（谁引用谁）

- device：被 alert_rule（device_id 可选）、alert_history（device_id）引用。  
- alert_rule：被 alert_history（rule_id）引用。  
- sys_user：被 user_role（user_id）、config_backup（user_id 可选）引用。  
- role：被 user_role（role_id）、role_menu（role_id）引用。  
- ai_chat_session：被 ai_chat_message（session_id）引用。  
- 其余表：audit_log、system_config、alert_template 不引用其他业务表。

## 4.3 绘制建议

- 以“表名”为矩形，内写主键及主要字段名。  
- 从“多”的一方画箭头指向“一”的一方，箭头上标外键名（如 rule_id、device_id、session_id）。  
- user_role、role_menu 作为中间表放在 sys_user 与 role、role 与“菜单”之间。  
- 可在图例中说明：device 与 InfluxDB/Redis 无表级外键，仅为业务关联。

---

# 第五部分：核心表结构简表（便于画表结构图）

以下给出核心表的“表名 + 主键 + 主要列”简表，便于绘制表结构图或与 ER 图对照。

| 表名 | 主键 | 主要列（选列） |
|------|------|----------------|
| device | id | name, ip, type, vendor, model, snmp_version, snmp_community, snmp_security, snmp_port, ssh_port, ssh_user, ssh_password, group_name, status, last_poll_time, deleted, create_time, update_time |
| alert_rule | id | name, device_id, device_types, metric_key, rule_condition, severity, enabled, notify_email, auto_fix_*, create_time, update_time |
| alert_history | id | rule_id, device_id, metric_key, trigger_value, start_time, end_time, status, severity, message, create_time, update_time |
| sys_user | id | username(UK), password, email, enabled, create_time, update_time |
| role | id | code(UK), name(UK), description, create_time, update_time |
| user_role | (user_id, role_id) | 联合主键，多对多 |
| role_menu | id | role_id, menu_code(UK: role_id+menu_code), 角色可分配菜单 |
| audit_log | id | username, action, target_type, target_id, detail, ip, create_time |
| system_config | id | config_key(UK), config_value, remark, create_time, update_time |
| config_backup | id | name, backup_type, summary, content(LONGTEXT), user_id, create_time |
| ai_chat_session | id | username, title, create_time |
| ai_chat_message | id | session_id(FK), role, content, create_time |

说明：UK 表示唯一约束，FK 表示外键关联；device 与 alert_rule、alert_history 通过 device_id 关联；alert_history 与 alert_rule 通过 rule_id 关联；ai_chat_message 与 ai_chat_session 通过 session_id 关联。

---

**说明**：正文已超过 1.2 万字（按中文字符计）。第二部分为总体 ER 流程与业务/数据流文字描述；第三部分为按功能模块的 ER 图描述；第四部分为数据库表关系图描述；第五部分为核心表结构简表。可直接用于论文插图说明或导入绘图工具（如 draw.io、Visio、PlantUML）绘制 ER 图、流程图与表关系图。
