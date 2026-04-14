# NetPulse UML 用例图

用例图描述**参与者（角色）**与**系统功能（用例）**之间的关系；本系统权限由 **角色—菜单模板**（`role_menu`）与 **ADMIN 全量菜单** 共同实现，图中为典型划分。

| 文件 | 说明 |
|------|------|
| `docs/NetPulse-用例图-总体.puml` | **总体用例图**：系统管理员、运维人员、只读用户三类参与者与主要用例连线 |
| `docs/NetPulse-用例图-系统管理员.puml` | **系统管理员**：全功能（含用户与角色、系统设置、设备敏感信息等） |
| `docs/NetPulse-用例图-运维人员.puml` | **运维人员**：常见运维与监控能力（不含用户管理） |
| `docs/NetPulse-用例图-只读用户.puml` | **只读用户**：以查看仪表盘、指标、拓扑、告警、巡检报告为主 |

## 与实现的关系

- 菜单编码见后端 `MenuConstants.ALL_MENU_CODES`（如 `dashboard`、`devices`、`users`、`system` 等）。
- 登录后 `allowedMenus` 决定可进入的路由；**ADMIN** 角色等价于拥有全部菜单。
- 设备 SSH/SNMP 密码等：**仅 ADMIN** 在前端可查看/编辑（见 `DeviceList.vue` 与注释）。

## 导出

使用 **PlantUML**（VS Code / Cursor 插件或 [在线渲染](https://www.plantuml.com/plantuml)）打开 `.puml`，导出 **PNG/SVG** 插入论文。

## Mermaid 备选（单参与者简图）

若环境无 PlantUML，可将总体关系用文字 + 下表代替，或使用 Mermaid `flowchart` 示意（非标准用例图）：

| 参与者 | 典型用例范围 |
|--------|----------------|
| 系统管理员 | 全部用例 |
| 运维人员 | 除「用户与角色管理」外多数运维用例；是否含「系统设置」依角色模板 |
| 只读用户 | 登录、仪表盘、查看类功能；一般不含远程运维、配置备份、用户管理、系统设置 |
