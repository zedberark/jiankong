# NetPulse UML 时序图

时序图描述对象之间**按时间顺序**的消息交互，适用于论文「登录流程」「典型业务调用链」等说明。

| 文件 | 场景 |
|------|------|
| `docs/NetPulse-时序图-用户登录.puml` | 用户登录：`AuthController`、密码校验、角色与菜单、`JWT` 签发 |
| `docs/NetPulse-时序图-设备管理.puml` | 保存设备：`DeviceController` → `DeviceService` → 库表 + 审计 |
| `docs/NetPulse-时序图-系统巡检.puml` | 手动巡检：`InspectionService` 探测、写报告、可选大模型生成结论 |
| `docs/NetPulse-时序图-AI运维助手.puml` | AI 对话：会话校验、上下文、调用大模型、持久化消息 |
| `docs/NetPulse-时序图-WebSocket终端.puml` | Web SSH：浏览器 WebSocket 与 SSH/Telnet 双向转发 |

## 导出

使用 **PlantUML**（VS Code / Cursor 插件或 [在线](https://www.plantuml.com/plantuml)）打开 `.puml`，导出 **PNG/SVG** 插入 Word。

## 与代码的对应关系

- 登录：`AuthController`、`UserService.getAllowedMenuCodes`、`AuthTokenService`。
- 巡检：`InspectionController.runInspection`、`InspectionService.runInspection`、`generateAiSummary`（可选）。
- AI：`AiChatController` → `AiChatService`（HTTP 调用 `RestTemplate` 至配置的 LLM）。
- Web SSH：Spring WebSocket 处理器与 `JSch`/`Commons Net`（具体类名以工程为准）。

## Mermaid 备选

若需 Mermaid `sequenceDiagram`，可将上述交互手工精简为单条主路径，复制到 [mermaid.live](https://mermaid.live) 导出；复杂分支以 PlantUML 为准。
