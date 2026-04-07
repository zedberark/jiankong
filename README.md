# NetPulse 监控系统（Nebula Ops）

类似 Zabbix 的监控系统，用于监控 **VM 虚拟机 Linux**、**eNSP 模拟器**中的防火墙/交换机/路由器，并提供 **Web SSH** 终端。后端已对接 **nebula_ops** 数据库与您提供的表结构。

- **后端**: Spring Boot 3 + MySQL(nebula_ops) + InfluxDB + Redis + RabbitMQ  
- **前端**: Vue 3 + Vite，白色主题  
- **数据库**: 使用您提供的 `nebula_ops` 建表脚本（device / monitor_item / alert_rule / alert_history / notification_log / sys_user / role / user_role / audit_log / webssh_session / sys_permission / role_permission）  
- **Docker**: MySQL / Redis / RabbitMQ / InfluxDB 均部署在 **192.168.1.160** 虚拟机 Linux 的 Docker 中，后端通过该 IP 连接  

---

## 一、数据库与 Docker 配置

### 1. 数据库

在 MySQL 中执行您提供的 **nebula_ops** 建表脚本，创建库 `nebula_ops` 及全部表。

**Web SSH 需要设备登录凭证**：若建表脚本中未包含 `ssh_user`、`ssh_password`，可执行（或由 JPA `ddl-auto=update` 自动加列）：

```sql
USE nebula_ops;
ALTER TABLE device
  ADD COLUMN IF NOT EXISTS ssh_user VARCHAR(64) NULL COMMENT 'SSH 用户名',
  ADD COLUMN IF NOT EXISTS ssh_password VARCHAR(256) NULL COMMENT 'SSH 密码（用于 Web SSH）';
```

（MySQL 8.0 部分版本不支持 `IF NOT EXISTS`，可改为直接 `ADD COLUMN ssh_user ...`，若已存在则忽略报错。）

**删除不需要的字段**：若需与当前代码一致，可执行项目根目录下 `migration_drop_unused_columns.sql`（会删除 `sys_user.phone` 等未使用列；执行前请备份）。

### 2. 与 Docker 环境对接（全部在 192.168.1.160 虚拟机）

**典型用法：本机 Windows 用 IDEA 运行 NetPulse 后端，连接虚拟机 Linux (192.168.1.160) 上的 MySQL、Redis、RabbitMQ、InfluxDB。**

所有中间件均部署在 **192.168.1.160** 的 Linux Docker 中，`application.yml` 默认已指向该 IP，在 Windows 上直接运行主类即可，无需改配置：

| 配置项 | 默认值（连接 192.168.1.160） | 说明 |
|--------|------------------------------|------|
| MySQL | `192.168.1.160:3309`，库 `nebula_ops` | 用户名 root，密码 root123456 |
| Redis | `192.168.1.160:6379` | 密码 root123456 |
| RabbitMQ | `192.168.1.160:5672` | 用户名 admin，密码 root123456 |
| InfluxDB | `http://192.168.1.160:8086` | org=nebula_org, bucket=device_metrics, token=nebula_token_2026 |

**要求**：Windows 本机要能访问 192.168.1.160（同一网段、虚拟机网络为桥接或 NAT 且端口已映射）。可在本机执行 `ping 192.168.1.160` 测试。

若 Docker 不在 192.168.1.160，可在 IDEA 运行配置或本机环境变量中设置：`MYSQL_HOST`、`REDIS_HOST`、`RABBITMQ_HOST`、`INFLUXDB_URL` 等覆盖默认 IP。

**启动失败：HikariPool-1 - Exception during pool initialization**  
说明 **MySQL 连接失败**（无法建立数据库连接）。若日志中有 **`Caused by: java.net.ConnectException: Connection timed out`**，表示本机连不上 **192.168.1.160**（虚拟机未开或网络不通）。

**方式一（推荐，无需 MySQL/Redis）**：用 **H2 内存库** 先跑通应用  
- 在 IDEA 中：Run → Edit Configurations → 找到 NetPulseApplication → **Active profiles** 填 **`local`** → 确定后重新运行。  
- 使用内置 H2 数据库，不依赖 192.168.1.160；数据在重启后清空，适合本地开发与演示。

**方式二**：连 192.168.1.160 上的 MySQL（需网络可达）  
1. 本机执行 `ping 192.168.1.160`，确认能通。  
2. 在 192.168.1.160 上确认 MySQL 已启动（如 Docker 容器），端口 **3309**，并已建库 `nebula_ops`、执行建表脚本，账号 root/root123456。

**方式三**：改用本机 MySQL（持久化数据）  
1. 本机安装并启动 MySQL（如 3306 端口），执行 `CREATE DATABASE nebula_ops;` 并执行项目建表脚本。  
2. 将 `NetPulse/src/main/resources/application-local-mysql.yml.example` 复制为 **`application-local-mysql.yml`**，修改其中的 `password` 为本机 MySQL 密码。  
3. 启动时 **Active profiles** 填 **`local-mysql`**。  
4. 若本机仍无法访问 192.168.1.160，Redis 会报错：需在本机安装 Redis 并设置环境变量 `REDIS_HOST=localhost`，或保证 192.168.1.160 可达。

其他错误可看日志 **Caused by:** 下一行（如 `Connection refused`、`Access denied`、`Unknown database 'nebula_ops'`）再排查。

**启动失败：Web server failed to start. Port 8082 was already in use**  
说明本机 8082 已被占用（例如已有一个 NetPulse 或其它程序在跑）。处理方式二选一：  
- **关闭占用端口的进程**：Windows 上 `netstat -ano | findstr :8082` 查 PID，再在任务管理器中结束该进程；或关闭已打开的 NetPulse/IDEA 运行实例。  
- **换端口启动**：环境变量 `SERVER_PORT=8083`（或 `application.yml` 里 `server.port: 8083`），则访问时用 `http://localhost:8083/api`。前端若需对接该端口，改 `netpulse-ui/.env` 或接口 baseURL。

#### 如何检查各服务是否正常

- **NetPulse 后端**的检查：在**能访问到后端的那台机器**上执行。
  - 若后端跑在 **192.168.1.160 虚拟机**：可以在该 **Linux 虚拟机里**执行，用 `http://localhost:8082` 或 `http://192.168.1.160:8082`。
  - 若后端跑在 **你本机 Windows（IDEA）**：在 **本机**打开 PowerShell 或 Git Bash，用 `http://localhost:8082`。
- **InfluxDB** 的检查：InfluxDB 在 192.168.1.160 的 Docker 里，所以要在**能访问 192.168.1.160 的机器**上执行（本机或 192.168.1.160 虚拟机里都可以）。

| 服务 | 检查命令 | 正常时 |
|------|----------|--------|
| **NetPulse 后端** | `curl -s -o /dev/null -w "%{http_code}" "http://192.168.1.160:8082/api/health"`（注意**必须带 /api**，否则会 404；本机则用 localhost:8082） | 返回 `200` |
| **InfluxDB** | `curl -s -o /dev/null -w "%{http_code}" "http://192.168.1.160:8086/health"` | 返回 `200` |
| **InfluxDB + 是否有数据** | `curl -s -H "X-User-Name: admin" "http://192.168.1.160:8082/api/metrics/influxdb-status"` | `reachable: true`、可选看 `hasRecentData` |

若 InfluxDB 能在浏览器打开（如 `http://192.168.1.160:8086/orgs/xxx`）且 `docker ps` 有容器，但后端 `/api/metrics/influxdb-status` 显示连接失败，请核对 `application.yml` 里 `influxdb.org`、`influxdb.bucket`、`influxdb.token` 与 InfluxDB 界面中的组织名、存储桶、Token 一致。

后端依赖的 MySQL / Redis / RabbitMQ 若启动失败，后端日志会报错；需要单独检查时可在 **192.168.1.160 虚拟机里**用 Docker 或对应客户端（如 `mysql -h 192.168.1.160 -P 3309 -u root -p`、`redis-cli -h 192.168.1.160 -a root123456 ping`）验证。

**网络设备 SNMP 采集（已默认关闭）**：  
当前 **SNMP 采集默认关闭**（`snmp.collect.enabled=false`），实时指标页不显示「网络设备」表。若需采集 **EVE-NG 模拟器** 中路由器/交换机/防火墙的 CPU、内存：  
1. 在 **application.yml** 中设置 `snmp.collect.enabled: true`（或 `SNMP_COLLECT_ENABLED=true`）并重启后端。  
2. 在 **设备管理** 中添加设备：填写管理 IP、类型（路由器/交换机/防火墙）、**SNMP**（v2c 社区名或 v3 用户名与认证/加密密码）、**SSH 用户名/密码**（用于 Web SSH 与告警修复）。  
3. 在 EVE-NG 设备上开启 SNMP、Ping、SSH 并配置与上面一致的 SNMP 和登录账号。  
详细步骤见 **[EVE-NG 网络设备监控配置指南](docs/EVE-NG网络设备监控配置指南.md)**。  
诊断接口：`curl -s -H "X-User-Name: admin" "http://localhost:8082/api/metrics/snmp-redis-status"`。

**监控趋势（CPU/内存）无数据**：页面上「监控趋势」显示的是 Telegraf 写入的 **cpu**、**mem** 时序。请确认 Telegraf 已配置 `[[inputs.cpu]]` 和 `[[inputs.mem]]`，且设备管理里该设备的「名称」与 Telegraf 的 host（或主机名）一致。

详细 InfluxDB 检查方式见下文 **七、检查 InfluxDB**。重新生成 Token 后需改哪些地方见 **八、重新生成 InfluxDB API Token 后需修改的地方**。

**CPU/内存折线图（device_metric）**：项目提供基于 `device_metric` 结构的折线图页面与接口，与 Telegraf 的 cpu/mem 为两套数据源。  
- 折线图页面：启动后端后访问 **http://localhost:8082/api/metric-chart.html**（设备ID、查询时长可调，点击「刷新数据」）。  
- 接口：`GET /api/metric/cpu-mem?deviceId=1&hours=1` 返回 CPU/内存时序 JSON。  
- 数据要求：InfluxDB 桶 `device_metrics` 中需有 measurement=`device_metric`，tag 含 `device_id`、`metric_key`（如 `cpu_usage`、`mem_usage`），field `value`。若无数据，可在 Linux 上进入 InfluxDB 容器后执行测试写入（将下方 `YOUR_TOKEN` 换为实际 Token）：

```bash
docker exec -it nebula-influxdb /bin/sh
for i in $(seq 1 10); do
  timestamp=$(date -d "-${i}min" +%s)000000000
  cpu_value=$((RANDOM % 50 + 40))
  mem_value=$((RANDOM % 40 + 30))
  influx write --bucket device_metrics --org nebula_org --token YOUR_TOKEN \
    "device_metric,device_id=1,metric_key=cpu_usage,unit=% value=${cpu_value} ${timestamp}"
  influx write --bucket device_metrics --org nebula_org --token YOUR_TOKEN \
    "device_metric,device_id=1,metric_key=mem_usage,unit=% value=${mem_value} ${timestamp}"
done
```

---

## 二、启动网页（dev / prod 两套）

要能正常打开并登录页面，需要**先启动后端，再启动前端**，最后在浏览器打开前端地址。  
后端默认端口 `8082`，前端默认端口 `5173`。

### 1) 开发环境（dev，默认）

`application.yml` 默认激活 `dev`：`SPRING_PROFILES_ACTIVE` 未设置时自动走 `dev`。  
`dev` 下允许使用开发默认值（如 `MYSQL_PASSWORD`、`AUTH_TOKEN_SECRET`），用于本地快速启动。

#### 后端启动（PowerShell）

```powershell
cd .\NetPulse
mvn spring-boot:run
```

#### 后端启动（Bash）

```bash
cd NetPulse
mvn spring-boot:run
```

看到日志 `Started NetPulseApplication` / `Tomcat started on port ... 8082` 即表示后端已就绪。

#### 前端启动

```bash
cd netpulse-ui
npm install
npm run dev
```

看到 `Local: http://localhost:5173/` 即表示前端已就绪。

#### 打开页面

访问 `http://localhost:5173`。

---

### 2) 生产环境（prod，强制环境变量）

`prod` 下不会使用开发默认密钥，必须显式提供关键环境变量，否则后端会在启动期失败（这是预期行为，用于避免弱配置上线）。

**至少需要设置：**
- `SPRING_PROFILES_ACTIVE=prod`
- `MYSQL_PASSWORD=<你的生产数据库密码>`
- `AUTH_TOKEN_SECRET=<长度至少16位的高强度密钥>`

可选：`JPA_DDL_AUTO=validate`（建议生产保持 `validate`）。

#### 后端启动（PowerShell）

```powershell
cd .\NetPulse
$env:SPRING_PROFILES_ACTIVE='prod'
$env:MYSQL_PASSWORD='你的生产数据库密码'
$env:AUTH_TOKEN_SECRET='请替换为16位以上高强度密钥'
mvn spring-boot:run
```

#### 后端启动（Bash）

```bash
cd NetPulse
export SPRING_PROFILES_ACTIVE=prod
export MYSQL_PASSWORD='你的生产数据库密码'
export AUTH_TOKEN_SECRET='请替换为16位以上高强度密钥'
mvn spring-boot:run
```

> 说明：若出现 `Could not resolve placeholder`、数据库认证失败、或 401/鉴权异常，优先检查上述变量是否已正确注入到**当前启动进程**。

---

### 3) 常见联调检查（避免“设备数据加载失败”）

1. 先确认后端端口在监听：`8082`。  
2. 再确认前端代理目标是 `http://localhost:8082`（见 `netpulse-ui/vite.config.js`）。  
3. 浏览器出现“登录过期”时先重新登录；该情况不是后端不可达。  
4. 若前端控制台出现 `/api/* ECONNREFUSED`，表示后端未启动或端口不通。

---

## 三、前端说明（生产构建等）

生产构建：在 `netpulse-ui` 下执行 `npm run build`，将生成的 `dist` 部署到静态服务器；前端通过 Vite 代理访问 `/api` 与 WebSocket `/api/ws/ssh`。  

---

## 四、功能模块概览

- **设备管理**：增删改查、**批量导入**（CSV 格式：名称,IP,类型）、**健康状态**（按 status 统计：normal/warning/critical/offline）。设备类型：server / router / switch / firewall / other，与表 `device` 一致。  
- **数据采集**：定时 Ping 设备管理 IP，更新 `device.status` 与 `last_poll_time`，并写入 InfluxDB（需配置 `influxdb.token`）。  
- **监控告警**：表结构已具备（alert_rule、alert_history、notification_log、monitor_item），后续可扩展规则引擎与通知发送。  
- **数据可视化**：仪表盘展示正常/告警/离线数量与设备列表；**实时指标**页区分 **Linux 设备**（InfluxDB/Telegraf、SSH）与 **网络设备**（SNMP/Redis），并提供数据来源、CPU、内存饼图。  
- **系统管理**：用户/角色/权限/审计日志/WebSSH 会话表已存在，可扩展登录与权限控制。  
- **Web SSH**：选择设备后连接，后端 WebSocket 代理到设备 SSH（需设备填写 `ssh_user`、`ssh_password`）。  

---

## 五、API 摘要

- `GET/POST/PUT/DELETE /api/devices`：设备列表、创建、更新、逻辑删除  
- `GET /api/devices/health`：健康汇总，返回各 status 数量  
- `POST /api/devices/import`：批量导入设备（JSON 数组）  
- `GET /api/devices/{id}/ping`：即时 Ping  
- `GET /api/metrics/summary`、`GET /api/metrics/device/{id}`：InfluxDB 指标（需配置 token）  
- WebSocket `/api/ws/ssh?deviceId=xxx`：Web SSH 终端  

---

## 六、项目结构

```
NetPulse/
├── pom.xml
├── src/main/java/org/ops/netpulse/
│   ├── entity/       # Device, MonitorItem, AlertRule, AlertHistory, SysUser, Role, ...
│   ├── repository/   # DeviceRepository, MonitorItemRepository, RoleRepository, ...
│   ├── service/      # DeviceService, MonitorService, WebSshService
│   ├── controller/   # DeviceController, MetricsController
│   ├── config/       # InfluxDB, WebSocket, CORS
│   └── websocket/    # SshWebSocketHandler
├── src/main/resources/application.yml
├── netpulse-ui/      # Vue 3 前端（白底）
└── README.md
```

若未配置 InfluxDB Token，定时 Ping 仍会执行并更新设备状态，仅不写入时序库；仪表盘使用 `/api/devices/health` 展示健康汇总。

### EVE-NG 模拟设备 + SNMP 采集（写入 Docker Redis）

使用 **EVE-NG** 模拟华为防火墙、路由器、锐捷等设备时，可在**设备管理**中按 **IP** 添加设备，并配置 SNMP（v2c 或 v3），由系统定时采集 **sysName、sysDescr、ifNumber、CPU** 并写入虚拟机 Linux 上 Docker 中的 **Redis**。

**配置步骤：**

1. **启用 SNMP 采集**：在 `application.yml` 中设置 `snmp.collect.enabled: true`，并确保 `spring.data.redis` 指向虚拟机 Redis（默认 `192.168.1.160:6379`，密码 `root123456`）。
2. **设备管理中添加设备**：名称、IP、类型（如 firewall / router / switch）、厂商（如华为、锐捷、Cisco）按需填写。
3. **SNMP 配置**：
   - **v2c**：在设备上填写 **SNMP Community**（如 `public`），不填则默认 `public`。
   - **v3**：在设备上填写 **SNMP 安全信息** 为 JSON，例如：  
     `{"username":"snmpuser","authPassword":"Auth@123456","privPassword":"Priv@123456"}`
4. **采集间隔**：默认每 30 秒执行一次（可配置 `snmp.collect.interval-ms`）。

**Redis 中的 key 与字段：**

- Key：`netpulse:snmp:device:{设备ID}`
- 类型：Hash  
- 字段：`ip`、`name`、`sysName`、`sysDescr`、`ifNumber`、`cpu`、`memory`、`lastCollectTime`（时间戳毫秒）

仅当设备**未删除**且**已配置 SNMP**（填写了 `snmp_community` 或 `snmp_security`）时才会被采集。CPU/内存按厂商自动选择 OID（华为/锐捷/Cisco 等），取第一个有效数值。**系统「实时指标」页会从 Redis 读取这些设备的 CPU、内存并展示**，与 Telegraf/SSH 采集的服务器指标同一张表。

---

## 七、检查 InfluxDB（在 Linux 虚拟机或本机）

在虚拟机的 Linux 上或本机用以下方式检查 InfluxDB 是否可用、是否有数据，**无需在页面上加按钮**。

### 1. 通过本应用状态接口（推荐）

后端提供 `GET /api/metrics/influxdb-status`，返回：是否已配置、服务是否可达、最近 5 分钟内是否有 cpu/mem/disk/net 数据。

在 **192.168.1.160 虚拟机**上执行（后端若在本机则用 localhost:8082，若也在虚拟机上则用 192.168.1.160:8082）：

```bash
curl -s -H "X-User-Name: admin" "http://localhost:8082/api/metrics/influxdb-status"
# 或后端在别机时：curl -s -H "X-User-Name: admin" "http://192.168.1.160:8082/api/metrics/influxdb-status"
```

返回示例：

- 未配置：`{"configured":false,"message":"未配置 InfluxDB：..."}`
- 配置了但连不上：`{"configured":true,"reachable":false,"message":"连接失败: ..."}`
- 正常且有数据：`{"configured":true,"reachable":true,"hasRecentData":true,"dataSummary":{"cpu":120,"mem":120,"disk":60,"net":240},...}`

### 2. 直接检查 InfluxDB 服务（URL：192.168.1.160:8086）

在 192.168.1.160 或能访问该 IP 的机器上：

```bash
# 健康检查（无需 token）
curl -s -o /dev/null -w "%{http_code}" "http://192.168.1.160:8086/health"
# 返回 200 表示服务在运行、网络可达
```

### 3. 使用 Influx CLI（可选）

若 192.168.1.160 上已安装 `influx` 命令行：

```bash
export INFLUX_URL=http://192.168.1.160:8086
export INFLUX_TOKEN=nebula_token_2026
influx query 'from(bucket:"device_metrics") |> range(start: -5m) |> limit(n: 1)'
```

能返回结果说明网络可达、token 有效、bucket 可读。

---

## 八、重新生成 InfluxDB API Token 后需修改的地方

在 InfluxDB 界面重新生成 API Token 后，**以下两处必须同步改成新 Token**，否则会连不上或写不进数据。

| 位置 | 修改内容 |
|------|----------|
| **本机 NetPulse 后端** | 编辑 `NetPulse/src/main/resources/application-secrets.yml`，把 `influxdb.token` 改成新 Token；若无该文件则用 IDEA 运行配置里的环境变量 `INFLUXDB_TOKEN=新Token`。改完后重启后端。 |
| **虚拟机 Linux 上的 Telegraf** | SSH 到 192.168.1.160，编辑 `/etc/telegraf/telegraf.conf`，在 `[[outputs.influxdb_v2]]` 段中把 `token = "旧Token"` 改成 `token = "新Token"`，保存后执行 `sudo systemctl restart telegraf`。 |

---

## 九、在 Linux 上检查 Telegraf 的 host 是否与设备 IP 一致

实时指标、监控趋势都按 **host** 匹配设备：Telegraf 上报的 `host` 必须与「设备管理」里该主机的 **管理 IP** 完全一致（如 `192.168.1.160`），否则页面上看不到 CPU/内存或趋势图。

### 1. 看 Telegraf 配置里的 host

在 **运行 Telegraf 的那台 Linux** 上执行：

```bash
# 若用 systemd 管理
sudo systemctl status telegraf
# 配置文件常见路径
grep -r "hostname\|host" /etc/telegraf/telegraf.conf 2>/dev/null || true
```

重点看 `[[outputs.influxdb_v2]]` 或主配置里是否显式设置了 `hostname = "192.168.1.160"`。未设置时，Telegraf 默认用本机主机名（如 `ubuntu`、`localhost`），就会和设备 IP 对不上。

### 2. 直接改 Telegraf 配置

编辑 `/etc/telegraf/telegraf.conf`（或你的实际配置文件），在 **全局** 或 **agent** 段中设置：

```toml
[agent]
  hostname = "192.168.1.160"
```

或通过环境变量启动 Telegraf：

```bash
export HOSTNAME=192.168.1.160
telegraf --config /etc/telegraf/telegraf.conf
```

改完后重启 Telegraf：`sudo systemctl restart telegraf`（或你的启动方式）。

### 3. 看 InfluxDB 里实际写入的 host

在 **192.168.1.160** 上若已装 `influx` CLI：

```bash
export INFLUX_URL=http://192.168.1.160:8086
export INFLUX_TOKEN=你的Token
# 看最近一条 cpu 数据的 host 标签
influx query 'from(bucket:"device_metrics") |> range(start: -1h) |> filter(fn: (r) => r._measurement == "cpu") |> last()' --raw
```

输出里会带 `host=xxx`，这里的 `xxx` 必须与设备管理里该主机的 **管理 IP** 一致。

### 4. 用本应用接口看

在能访问 NetPulse 后端的机器上执行（后端需已连 InfluxDB）：

```bash
curl -s -H "X-User-Name: admin" "http://192.168.1.160:8082/api/metrics/influxdb-status"
```

看返回里的 `dataSummary` 是否有近期数据；若 `reachable: true` 但 `hasRecentData: false`，多半是 Telegraf 的 `host` 与设备 IP 不一致，按上面 1～3 步在 Linux 上改 Telegraf 的 host 并重启。

---

## 十、常见问题（启动失败等）

### 1. 后端启动报错：Port 8082 was already in use

**原因**：本机已有进程占用 8082 端口（例如之前未关闭的 NetPulse 或其它服务）。默认端口已改为 **8082**。

**处理方式任选其一：**

- **释放 8082 端口**（Windows PowerShell）：
  ```powershell
  netstat -ano | findstr :8082
  taskkill /PID <上一步看到的 PID> /F
  ```
- **改用其它端口**：在 `application.yml` 所在目录或环境变量中设置 `SERVER_PORT`，例如：
  ```bash
  set SERVER_PORT=8081
  ```
  或在 `application-local.yml` 中增加：
  ```yaml
  server:
    port: 8081
  ```
  然后重启后端；前端若通过代理访问，需保证代理目标端口与上述一致。

### 2. 启动失败时如何看完整错误

若控制台只看到 `LoggingFailureAnalysisReporter` 的一行 ERROR，请向下滚动查看 **Description:** 和 **Action:**，或重新运行并加上 debug：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--debug"
```

---

## 十一、华为 USG6000V2 防火墙 SNMPv3 采集

后端提供 SNMPv3 定时采集（每 30 秒），将防火墙的 **sysName**、**sysDescr**、**ifNumber** 写入 Docker Redis。

### 1. 启用与配置

- 在 `application.yml` 中已预设：
  - **snmp.collect.enabled**: `true` 时启用采集并写入 Redis（使用 `spring.data.redis` 配置，默认 192.168.1.160:6379，密码 root123456）。
  - **snmp.firewall.host**: 防火墙 IP（默认 192.168.1.160，EVE 中请改为实际防火墙管理 IP，如 `SNMP_FIREWALL_HOST=10.x.x.x`）。
  - **snmp.firewall.port**: 161。
  - **snmp.firewall.username** / **auth-password** / **priv-password**：须与防火墙 SNMPv3 配置一致（默认 snmpuser / Auth@123456 / Priv@123456，认证 SHA，加密 AES-128，authPriv）。

- 采集 OID：`1.3.6.1.2.1.1.5.0`（sysName）、`1.3.6.1.2.1.1.1.0`（sysDescr）、`1.3.6.1.2.1.2.1.0`（ifNumber）。

### 2. Redis 中的结果

- Key：`netpulse:snmp:firewall:data`（Hash）。
- 字段：`sysName`、`sysDescr`、`ifNumber` 及对应 OID 键，值为采集到的字符串。
- 最近一次采集时间：`netpulse:snmp:firewall:lastCollectTime`（毫秒时间戳）。

### 3. 关闭采集

设置 `snmp.collect.enabled=false` 或环境变量 `SNMP_COLLECT_ENABLED=false`，则不再执行 SNMP 采集与 Redis 写入。
