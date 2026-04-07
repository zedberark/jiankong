# EVE-NG 模拟器网络设备接入 NetPulse 配置指南

通过 SNMP 采集 EVE-NG 中路由器/交换机/防火墙的 CPU、内存，并在 NetPulse 实时指标页查看。你只需：在 EVE-NG 里给设备配好 IP，在设备上开启 SNMP、Ping、SSH 及用户密码，然后在 NetPulse 设备列表中添加该设备并填写对应配置。

---

## 从零开始：创建思科设备并接入 NetPulse

下面按顺序做一遍：在 EVE-NG 里新建一台思科路由器 → 配好 IP、SSH、SNMP → 在 NetPulse 里添加设备并看到 CPU/内存。

### 设备选择建议

| 设备 | 说明 | 推荐 |
|------|------|------|
| **Cisco vIOS Router** | 虚拟 IOS 路由器，标准 IOS 命令 | ✅ 首选，命令与下文一致 |
| **Cisco IOL** | IOS on Linux，轻量，标准 IOS | ✅ 同样适用 |
| Cisco vIOS Switch | 虚拟 IOS 交换机，命令类似 | 可选 |
| Cisco CSR 1000V (XE) | IOS XE，语法略有差异 | 进阶 |
| Cisco ASAv / NX-OSv / XRv | 防火墙/数据中心/XR，CLI 不同 | 暂不推荐第一次用 |

**建议第一次选「Cisco vIOS Router」或「Cisco IOL」**，然后按下面步骤做即可。NetPulse 已支持该类设备的 CPU（OLD-CISCO-CPU-MIB `1.3.6.1.4.1.9.2.1.58.0`）与内存（ciscoMemoryPoolUsed/Free `1.3.6.1.4.1.9.9.48.1.1.1.5.1` / `.6.1`）OID，设备类型选「路由器」即可自动尝试这些 OID。

### 第一步：EVE-NG 里创建拓扑并启动思科设备

1. 打开 **EVE-NG**，新建一个 Lab（或使用现有 Lab）。
2. 在拓扑里 **添加节点**：选 **Cisco vIOS Router**（或 **Cisco IOL**）。
3. 把该节点连到 **云（Cloud0）** 或能和你本机/NetPulse 互通的那张网卡（这样 NetPulse 能 Ping 和 SNMP 到设备）。
4. **启动设备**：右键设备 → Start，等设备完全起来（图标变绿）。
5. 右键设备 → **Console**，用浏览器或终端连进设备 CLI（首次可能是空配置或默认配置）。

### 第二步：在思科设备上从零配置（按顺序在 CLI 里敲）

Console 连上后，先进入**全局配置模式**（如已是 `#` 提示符则已在配置模式）：

```text
enable
configure terminal
```

下面是一整套可复制粘贴的配置（**把 192.168.1.101 改成你打算用的管理 IP**，密码可按需改）。  
假设：管理网段 `192.168.1.0/24`，路由器 IP `192.168.1.101`，NetPulse 同网段。

```text
! 1）主机名（可选）
hostname R1

! 2）本地用户：SSH 登录用（用户名 admin，密码 Admin@123）
username admin privilege 15 secret Admin@123

! 3）查一下接口名（vIOS Router 常见是 GigabitEthernet0/0 或 0/1）
! 可先敲： show ip interface brief
! 4）给接口配管理 IP（接口名不对就改成你 show 出来的，如 Gi0/1）
interface GigabitEthernet0/0
 ip address 192.168.1.101 255.255.255.0
 no shutdown
exit

! 5）域名（SSH 必需）
ip domain-name local

! 6）生成 RSA 密钥（会问 y/n，选 y）
crypto key generate rsa modulus 1024

! 7）VTY 只允许 SSH，用本地账号登录
line vty 0 4
 transport input ssh
 login local
exit

! 8）SNMP：先用 v2c，能采到数据再考虑 v3；若 v2c 不通再改 v3
snmp-server community public RO

! 9）退回特权模式并保存
end
write memory
```

**建议先用 v2c**（上面一条即可）。若 v2c 在 NetPulse 里采不到数据、或你希望改用 v3，再在设备上删 v2c 并配 v3（与 v2c 二选一）：

```text
no snmp-server community public RO
snmp-server group MyGroup v3 priv
snmp-server user snmpuser MyGroup v3 auth sha Auth@123 priv aes 128 Priv@123
write memory
```
NetPulse 里该设备改为 SNMP v3，用户名 `snmpuser`，认证密码 `Auth@123`，加密密码 `Priv@123`。

**注意**：若你还没配过本地用户，SSH 会无法登录。在 `R1(config)#` 下补一条（密码可自改）：
```text
username admin privilege 15 secret Admin@123
```
再 `end` 和 `write memory`。

配置完成后，在本机或 NetPulse 所在机器 **Ping 管理 IP**，应能通；再试 **SSH**（如 `ssh admin@192.168.1.107`），能登录即可。

### 第三步：NetPulse 启用 SNMP 并添加该设备

1. **启用 SNMP 采集**  
   在 NetPulse 的 `application.yml` 里设 `snmp.collect.enabled: true`（或环境变量 `SNMP_COLLECT_ENABLED=true`），重启后端。

2. **设备管理里添加设备**  
   - 登录 NetPulse → **设备管理** → **添加设备**。  
   - **名称**：如 `EVE-R1`。  
   - **类型**：**路由器**。  
   - **管理 IP**：设备上配的 IP（如 `192.168.1.107`）。  
   - **SSH 端口**：`22`。  
   - **SSH 用户名** / **SSH 密码**：与设备上 `username ... secret` 一致（若按文档配则为 `admin` / `Admin@123`）。  
   - **SNMP 版本**：设备用了 v2c 就选 **v2c**，**SNMP 社区名** 填 `public`；若用了 v3，选 **v3** 并填对应用户名与两个密码。  
   - 保存。

3. **看数据**  
   打开 **实时指标** 页，在「网络设备列表（CPU / 内存）」中会**现场直采**（不经过 Redis），刷新页面即可看到该设备的 CPU、内存；若已开启 SNMP 采集，可点 **实时采集** 立即再采一次。

**按你当前 R1（192.168.1.107）的填写示例：**

| 字段       | 填写内容        |
|------------|-----------------|
| 名称       | R1 或 EVE-R1    |
| 类型       | 路由器          |
| 管理 IP    | 192.168.1.107  |
| SSH 端口   | 22              |
| SSH 用户名 | admin（若已配） |
| SSH 密码   | Admin@123（若已配） |
| SNMP 版本  | v2c             |
| SNMP 社区名| public          |

若设备上还没配 `username admin privilege 15 secret Admin@123`，Web SSH 会登不上，需到路由器上补配并保存。

---

### 下一步：确认 SNMP 采集

SSH 能用后，要看到 CPU/内存需要 SNMP 采集正常：

1. **NetPulse 已启用 SNMP**  
   `application.yml` 里 `snmp.collect.enabled: true` 并已重启后端。

2. **设备里已填 SNMP**  
   在 **设备管理** 中编辑该设备，确认：  
   - **类型** 为路由器/交换机/防火墙（不能是「服务器」）；  
   - **SNMP 版本** 选 v2c，**SNMP 社区名** 填 `public`（与设备上 `snmp-server community public RO` 一致）。  
   保存。

3. **看数据**  
   打开 **实时指标**，下方「网络设备列表（CPU / 内存）」会按请求**直采** SNMP，刷新页面即可看到该设备及 CPU、内存。可点该设备的 **「实时采集」** 立即再采一次。

4. **若无数据**  
   - 在运行 NetPulse 的机器上确认能 **ping 通** 设备管理 IP；  
   - 看后端日志是否有「SNMP 采集异常」或「响应为空」（排查 UDP 161、社区名、防火墙）；  
   - 调用诊断接口：  
     `curl -s -H "X-User-Name: admin" "http://localhost:8082/api/metrics/snmp-redis-status"`  
     看 `snmpMode`（当前为 `direct` 直采）、`hint` 提示。  
   - **v2c 仍不通时**再考虑改用 SNMP v3（见下文）。

**v2c 不通时再改用 SNMP v3**  
先保证 v2c 能采到数据；若 v2c 一直失败，再在路由器上删 v2c、配 v3：

```text
no snmp-server community public RO
snmp-server group MyGroup v3 priv
snmp-server user snmpuser MyGroup v3 auth sha Auth@123 priv aes 128 Priv@123
write memory
```

NetPulse 设备管理里该设备改为：SNMP 版本 **v3**，用户名 `snmpuser`，认证密码 `Auth@123`，加密密码 `Priv@123`。

---

按以上三步做完，就完成从零创建思科设备并接入 NetPulse 的流程。多台设备时重复「第二步（每台改 IP/主机名） + 第三步在 NetPulse 里再添加一条设备」即可。

---

## 一、整体流程

| 步骤 | 你在做的事 |
|------|------------|
| 1 | 在 EVE-NG 中给网络设备配置**管理 IP**（与 NetPulse 所在网络互通） |
| 2 | 在 EVE-NG 设备上**开启 SNMP**（v2c 或 v3）、**开启 SSH**、确保 **Ping 可达**，并配置**登录用户名和密码** |
| 3 | 在 NetPulse **启用 SNMP 采集**（见下文） |
| 4 | 在 NetPulse **设备管理**中**添加设备**：填写管理 IP、类型、SNMP 与 SSH 用户/密码 |
| 5 | 在 **实时指标** 页查看该设备的 CPU/内存（网络设备表） |

---

## 二、EVE-NG 侧：网络设备上的配置

### 2.1 管理 IP

- 为设备配置一个与 **运行 NetPulse 的机器** 能互通的 IP（例如同一网段）。
- 后续在 NetPulse 里添加设备时，**管理 IP** 就填这个地址。

### 2.2 开启并配置 SNMP（先用 v2c，不行再用 v3）

**建议先用 v2c**：配置简单，先确认能采到数据再考虑 v3。

**方式 A：SNMP v2c（首选，先试这个）**

- 在设备上启用 SNMP，并设置 **社区名**（如 `public`）。
- 示例（Cisco 风格）：
  ```text
  snmp-server community public RO
  ```
- 华为/锐捷等设备在 Web 或 CLI 中启用 SNMP v2c 并设置相同社区名即可。  
- NetPulse 里选 **v2c**、社区名填 `public`。能正常看到 CPU/内存即可；若 v2c 不通再改用 v3。

**方式 B：SNMP v3（v2c 不通或需要更安全时再用）**

- 在设备上创建 SNMP v3 用户，并设置：
  - **认证**：SHA
  - **加密**：AES-128
  - **认证密码**、**加密密码**（与 NetPulse 设备管理中填写的完全一致）
- 示例（Cisco 风格）：
  ```text
  snmp-server group MyGroup v3 priv
  snmp-server user snmpuser MyGroup v3 auth sha AuthPass123 priv aes 128 PrivPass123
  ```
- 华为/锐捷等：在 SNMP 配置中选择 v3、认证算法 SHA、加密算法 AES-128，并填写用户名与两个密码。

### 2.3 开启 Ping（ICMP）

- 确保设备允许 **ICMP**，以便 NetPulse 做在线检测（Ping）。
- 多数模拟设备默认允许；若有 ACL，需放行来自 NetPulse 的 ICMP。

### 2.4 开启 SSH 并配置用户和密码

- 在设备上启用 **SSH 服务**。
- 创建用于登录的 **用户名** 和 **密码**（与 NetPulse 设备管理中「SSH 用户名」「SSH 密码」一致，用于 Web SSH 与告警自动修复等）。
- 示例（Cisco 风格）：
  ```text
  username admin privilege 15 secret your_password
  ip domain-name local
  crypto key generate rsa modulus 1024
  line vty 0 4
   transport input ssh
   login local
  ```

---

## 三、NetPulse 侧配置

### 3.1 启用 SNMP 采集

- 在 **application.yml** 中设置：
  ```yaml
  snmp:
    collect:
      enabled: true
  ```
  或设置环境变量：`SNMP_COLLECT_ENABLED=true`
- 重启 NetPulse 后端。
- 当前为 **SNMP 直采**（请求实时指标时现场采集，默认不经过 Redis）。若需将采集结果写入 Redis，可另设 `snmp.use-redis: true`。

### 3.2 在设备列表中添加设备

1. 登录 NetPulse，进入 **设备管理**。
2. 点击 **添加设备**。
3. 填写：
   - **名称**：自定义（如 `EVE-路由器-1`）。
   - **类型**：**路由器** / **交换机** / **防火墙**（与 EVE-NG 中设备类型一致，影响 SNMP OID 选择）。
   - **管理 IP**：EVE-NG 中为该设备配置的 **管理 IP**。
   - **SSH 端口**：一般为 `22`（若设备改了端口则填实际端口）。
   - **SSH 用户名** / **SSH 密码**：与 EVE-NG 设备上配置的登录用户、密码一致（用于 Web SSH、告警自动修复）。
   - **SNMP 版本**：
     - 选 **v2c** 时填写 **SNMP 社区名**（与设备上一致，如 `public`）。
     - 选 **v3** 时填写 **SNMP 用户名**、**认证密码**、**加密密码**（与设备上 v3 用户一致，算法为 SHA + AES-128）。
   - **分组/备注** 等按需填写。
4. 保存。

### 3.3 查看数据

- 打开 **实时指标** 页，下方会出现 **「网络设备列表（CPU / 内存）」** 表格（仅显示类型为路由器/交换机/防火墙的设备）。
- 实时指标里的**网络设备**数据来自 **Redis**（按设备 IP 取 CPU/内存）。若 EVE 或采集脚本已把数据写入 Redis，刷新页面即可看到。
- 可点击某设备的 **「实时采集」** 立即再采一次并刷新。

### 3.4 Redis 在虚拟机 Docker 中的位置与查看

**Redis 部署**：在 **虚拟机 Linux 192.168.1.160** 的 Docker 中，NetPulse 通过 `spring.data.redis` 连接该 Redis（默认 `192.168.1.160:6379`，密码 `root123456`）。

**在虚拟机上进入 redis-cli**（在 192.168.1.160 上执行）：

```bash
# 先查 Redis 容器 ID：docker ps | grep redis
docker exec -it 6462b099fa5c redis-cli -a root123456
```

> 提示：`-a root123456` 在命令行中会提示 “Using a password with '-a' or '-u' option may not be safe”，仅方便调试；生产环境建议用配置文件或环境变量传密码。

**查看某设备的 CPU/内存数据**（key 格式为 `device:{设备名}`，如 `device:R1`）：

```bash
HGETALL device:R1
```

返回示例（Hash 字段：`ip`、`cpu`、`mem`、`collect_time`、`vendor`、`remark` 等）：

```text
1) "ip"
2) "192.168.1.107"
3) "vendor"
4) "cisco"
5) "cpu"
6) "3"
7) "mem"
8) "7.59"
9) "remark"
10) "思科路由器"
11) "collect_time"
12) "1773280025"
```

NetPulse 实时指标会按设备 **管理 IP** 与 Redis Hash 中的 **ip** 匹配，显示对应的 CPU、内存。

### 3.5 十分钟仍看不到网络设备数据时排查

网络设备 CPU/内存**仅从 Redis 按 IP 读取，不做 SNMP 采集**。实时指标页每 10 秒自动刷新；若**超过十分钟**仍没有数据，按下面顺序查：

**1）确认 Redis 可达且 key 存在**

- 后端需能连上 **192.168.1.160:6379**（密码 `root123456`）。若 NetPulse 跑在别机，需能访问该 IP 和 6379 端口。
- 在浏览器或本机执行（带登录态时把 `X-User-Name: admin` 换成你的登录方式）：
  ```bash
  curl -s -H "X-User-Name: admin" "http://localhost:8082/api/metrics/snmp-redis-status"
  ```
- 看返回：`redisAvailable` 应为 `true`，`redisSnmpKeyCount` 应 ≥ 1（表示 Redis 里至少有 `device:*` 或 `netpulse:snmp:device:*`）。若为 0，说明 Redis 里还没有设备数据，需由 EVE/脚本往 `device:R1` 等 key 写入。

**2）设备管理里必须有「同 IP」且类型非「服务器」的设备**

- 实时指标按 **管理 IP** 从 Redis 取数。例如 Redis 里是 `device:R1`、`ip=192.168.1.107`，则设备管理里必须有一条设备的 **管理 IP = 192.168.1.107**（一字不差）。
- 该设备 **类型** 须为 **路由器 / 交换机 / 防火墙 / 其他**，不能是「服务器」（服务器会出现在上方 Linux 表，走 Telegraf/SSH）。
- 若没有该设备，到 **设备管理** 新增，管理 IP 填 `192.168.1.107`，类型选路由器等。

**3）手动点一次「刷新」**

- 页面每 10 秒自动刷新，若刚改完配置或刚写入 Redis，可点一次 **「刷新」** 或重新打开实时指标页。

**4）仍无数据时看接口返回**

- 调用：`curl -s -H "X-User-Name: admin" "http://localhost:8082/api/metrics/realtime"`。
- 在返回的 `devices` 里找到管理 IP 为 192.168.1.107 的那条，记下 `id`。再看 `stats` 里是否有该 `id` 的 cpuPercent/memoryPercent，`statsSource` 里该 id 是否为 `snmp`。若 `stats` 里没有或 `statsSource` 不是 `snmp`，说明后端从 Redis 按 IP 未命中（再核对 IP 一致、Redis key 中 `ip` 字段值）。

---

## 四、EVE 模拟器数据通不通自检

在「没有数据」时，先确认从 **运行 NetPulse 的机器** 到 **EVE 里设备** 的链路是否通畅，再查 SNMP 是否配对。

### 4.1 在 NetPulse 本机用命令行自检

在 **运行 NetPulse 后端的服务器** 上执行（把 `192.168.1.101` 换成你设备的管理 IP）：

**1）Ping 设备（看网络是否通）**

```bash
ping -c 3 192.168.1.101
```

- 能通：说明 NetPulse 所在机与 EVE 设备 IP 互通，继续下一步。
- 不通：检查 EVE-NG 拓扑里设备是否连到 Cloud0/正确网卡、设备是否已启动、本机与 EVE 是否同网段或路由可达。

**2）SNMP 是否可达（v2c 示例，需本机已装 net-snmp）**

```bash
# v2c，社区名 public
snmpget -v2c -c public 192.168.1.101 1.3.6.1.2.1.1.5.0
```

- 有返回（如 `SNMPv2-MIB::sysName.0 = STRING: R1`）：说明设备 SNMP 已开、社区名正确、UDP 161 可达。
- 超时或 no response：检查设备上是否执行了 `snmp-server community public RO`、防火墙/安全组是否放行 **UDP 161**、设备管理 IP 是否填对。

**3）用 NetPulse 接口做连通性检测（推荐）**

在设备管理里添加好该设备后，用接口一次看 **Ping + SNMP** 结果（需带登录态或内网直接调）：

```bash
# 替换 {设备ID} 为实际 ID，端口 8082 若改了则一起改
curl -s -H "X-User-Name: admin" "http://localhost:8082/api/devices/{设备ID}/connectivity"
```

返回示例：

```json
{
  "deviceId": 1,
  "ip": "192.168.1.101",
  "name": "EVE-R1",
  "pingRttMs": 2,
  "pingOk": true,
  "snmpConfigured": true,
  "snmpOk": true,
  "hint": ""
}
```

- `pingOk: true`：Ping 通。
- `snmpOk: true`：SNMP 可达（用设备管理里填的社区名/v3 现场测）。
- `pingOk: false` 或 `snmpOk: false`：根据 `hint` 提示排查（如 Ping 不通先查网络，SNMP 不通查社区名/v3、UDP 161）。

仅测 Ping 也可用：

```bash
curl -s -H "X-User-Name: admin" "http://localhost:8082/api/devices/{设备ID}/ping"
```

### 4.2 自检通过仍无数据时

- 确认 **实时指标** 页「网络设备列表」里类型为 **路由器/交换机/防火墙**（不是「服务器」）。
- 确认 `application.yml` 里 **snmp.collect.enabled: true** 并已重启后端。
- 看后端日志是否有「SNMP 采集异常」或「响应为空」，结合 `hint` 调整设备 SNMP 或超时等。

---

## 五、常见问题

| 现象 | 排查方向 |
|------|----------|
| 实时指标里「网络设备列表」为空 | 1）设备管理里该设备的**类型**必须为「路由器/交换机/防火墙」（不能选「服务器」）；2）确认已有至少一台类型为非服务器的设备。 |
| 列表有设备但 CPU/内存为 — | 1）`snmp.collect.enabled` 是否为 `true` 并已重启；2）设备管理里是否填了 **SNMP 社区名（v2c）** 或 **v3 用户名与密码**；3）后端与设备 **UDP 161** 是否互通（防火墙/安全组）。 |
| **Linux 能采到、Cisco 在 NetPulse 里仍没有** | 1）NetPulse 后端必须跑在**能访问 Cisco 设备**的机器上（与你在本机用 `snmpget` 能通的机器一致或网络等价）；2）设备管理里该设备**类型**选「路由器」、**SNMP 版本**选 v2c、**SNMP 社区名**填 `public`；3）`application.yml` 里 **snmp.collect.enabled: true** 并已重启；4）调用 `GET /api/devices/{设备ID}/connectivity` 看 **pingOk**、**snmpOk**；5）看后端日志是否有「CPU OID 请求超时」等。 |
| 能取主机名但 CPU/内存超时 | 已改为 **CPU 与内存分两次请求**，单次 OID 更少不易超时。若仍超时：在 application.yml 中把 `snmp.firewall.timeout-ms` 调大（如 **10000～15000**），或设环境变量 `SNMP_TIMEOUT_MS=15000` 后重启。 |
| 日志报「SNMP 响应为空」 | 1）设备上 SNMP 是否已开、社区名或 v3 用户名密码是否与 NetPulse 完全一致；2）v3 是否使用 SHA + AES-128；3）可适当增大 `snmp.firewall.timeout-ms`（如 10000～15000）。 |
| Ping 不通 / 设备显示离线 | 检查 EVE-NG 网络与 NetPulse 所在网络是否互通、设备管理 IP 是否填对、设备是否允许 ICMP。 |
| Web SSH 连不上 | 检查设备是否开启 SSH、设备管理里 SSH 端口/用户名/密码是否与设备一致。 |

---

## 六、小结

- **你负责**：在 EVE-NG 里给设备配好 **IP**，并在设备上开启 **SNMP（v2c 或 v3）**、**Ping**、**SSH**，以及配置 **登录用户名和密码**。
- **NetPulse 负责**：在 **设备管理** 中添加该 IP 的设备并填写 **SNMP 与 SSH 信息**，启用 **SNMP 采集** 后，在 **实时指标** 中展示 CPU/内存；SSH 信息用于 Web SSH 与告警自动修复。

按上述步骤配置后，即可通过 SNMP 采集 EVE-NG 模拟器的网络设备 CPU 和内存，并在 NetPulse 中统一查看。
