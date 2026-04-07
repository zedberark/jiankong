# 网络设备 SSH/Telnet 采集与排查

## 一、数据流（你要的完整链路）

1. **厂商自查**：对网络设备执行 `display version` 或 `show version`，从输出里解析厂商（Huawei、H3C、Cisco、Ruijie、Juniper 等）。例如锐捷设备输出中有 `System description : Ruijie (X86 TESTBENCH) by Ruijie Networks.`，即识别为锐捷并用于回写设备列表的「厂商」。
2. **回写设备列表**：**仅当设备管理里该设备的「厂商」为空时**，用自查结果更新设备表的 `vendor`（及可选 `model`），设备列表里就能看到厂商（如 Ruijie）。
3. **按厂商查 CPU/内存**：根据识别到的厂商选命令（锐捷：`show cpu`、`show memory`；华为/H3C：`display cpu-usage` / `display memory-usage`；思科：`show processes cpu` / `show memory`），执行后解析出 CPU、内存使用率（锐捷 CPU 匹配 `CPU utilization in five seconds: 2.80%` → 2.80，内存匹配 `74.9% used rate` → 74.9）。
4. **写入内存缓存**：以 **设备 IP** 为维度，将解析结果写入进程内缓存（**暂不写 Redis**），供实时指标接口直接读取。
5. **反馈前端**：GET /metrics/realtime 优先按设备 IP 从 SSH/Telnet 缓存取数据；前端「设备指标」页的「**网络设备列表（CPU/内存）**」展示这些 CPU/内存数据。

## 二、何时会触发这条链路

| 触发方式 | 说明 |
|----------|------|
| **SNMP 采集失败自动回退** | 定时 SNMP 采到某台网络设备失败时，若该设备已配置 SSH/Telnet 用户名密码且 `ssh-collect.enabled=true`，会自动用 SSH/Telnet 再采一次（厂商自查 → 回写厂商 → CPU/内存写缓存并反馈前端）。 |
| **定时 SSH/Telnet 采集** | `ssh-collect.enabled=true` 时，每 40 秒对「在线、类型非服务器、已配 SSH 或 Telnet 用户名密码」的网络设备执行一次上述流程（端口 22 走 SSH、23 走 Telnet）。 |
| **手动「立即采集」** | 设备管理里对某设备点「立即采集」：先走 SNMP，失败则回退 SSH/Telnet；或直接调用 SSH/Telnet 采集接口。 |
| **一键刷新** | **设备指标页**点「刷新」会触发后端执行网络设备 SSH/Telnet 采集（厂商命令→写缓存），再拉取即可看到数据。设备管理里「立即采集」/「一键刷新」也会触发。 |

## 三、如何检查「有没有触发」

### 1. 看后端日志（最直接）

在应用日志里搜下面关键字（按时间顺序）：

- **SNMP 失败并尝试回退**  
  `尝试改用 SSH/Telnet 采集` 或 `SNMP 失败，正在回退 SSH/Telnet 采集`
- **未回退原因**  
  `未回退 SSH/Telnet：设备 ... 未配置 SSH/Telnet 用户名或密码`  
  `未回退 SSH/Telnet：SSH/Telnet 采集未启用（请设置 ssh-collect.enabled=true 并重启）`
- **SSH/Telnet 采集真正开始**  
  `开始 SSH/Telnet 采集：id=... ip=... name=...（厂商自查→选 CPU/内存命令→写 Redis）`
- **厂商识别与回写**  
  `厂商自查结果 id=... ip=... 识别到 vendor=... model=...`  
  `已用自查结果回写设备厂商 id=... ip=... vendor=... model=...`（只有设备原来厂商为空才会出现）
- **写入 Redis**  
  `SSH 采集已写入 Redis id=... ip=... cpu=...% memory=...% key=...`
- **未触发或中途跳过**  
  `SSH 采集跳过：版本命令无输出，无法识别厂商`（说明版本命令没拿到输出，不会写 Redis）

若一条「开始 SSH/Telnet 采集」都没有，说明这条链路没触发，重点看上面两条「未回退」日志和配置。

### 2. 看 Redis 里是否有该 IP 的数据

用设备管理里该网络设备的 **IP** 查 Redis（key 以 IP 为后缀）：

```bash
redis-cli HGETALL netpulse:snmp:device:ip:192.168.1.1
```

（把 `192.168.1.1` 换成实际 IP。）

若有数据，应能看到 `cpu`、`memory`、`lastCollectTime` 等字段；没有则说明要么没触发采集，要么采集失败/未写入。

### 3. 看设备列表里的「厂商」

- 若**之前厂商为空**，触发过一次成功的「厂商自查」后，设备管理里该设备的「厂商」会被自动填上（如 Huawei、Cisco）。
- 若厂商一直为空，可能是：没触发 SSH/Telnet 采集，或版本命令无输出、解析不到厂商。

### 4. 看前端「设备指标」页

「网络设备列表（CPU/内存）」里，每一行对应设备管理里的一个网络设备（按 **IP** 从 Redis 读）。若某设备 IP 在 Redis 中有 `netpulse:snmp:device:ip:{ip}` 且含 `cpu`/`memory`，该行就会显示 CPU/内存；否则显示为「—」或无数据。

## 四、为什么可能「没有触发」或「某台设备（如 192.168.1.31）没被采集」

**先看跳过原因（推荐）**：把该包日志级别设为 DEBUG，再点一次设备指标页或设备管理里的「刷新」/一键采集，或在下次定时采集（约 40 秒）后，在日志里搜该设备 IP（如 `192.168.1.31`）或「SSH/Telnet 采集跳过」。会看到具体原因，例如：
- `原因：类型为服务器` → 设备类型要设为路由器/交换机/防火墙，不能是服务器
- `原因：设备离线` → 设备状态为离线，不会采
- `原因：未配置 SSH/Telnet 用户名或密码` → 在设备管理里为该设备填 SSH 或 Telnet 用户名、密码
- `原因：IP 为空` → 设备未填 IP

配置示例（`application.yml` 或 `logback-spring.xml`）：
```yaml
logging:
  level:
    org.ops.netpulse.service.DeviceSshCollectService: DEBUG
```

**其他常见原因：**
- **未开启 SSH/Telnet 采集**：配置里没有 `ssh-collect.enabled=true`，或未重启，SNMP 失败时不会回退，定时也不会跑 SSH 采集。
- **设备未配 SSH/Telnet**：设备管理里该设备没填用户名、密码或端口（22/23），回退和定时采集都会跳过。
- **设备类型是「服务器」**：只对类型为路由器/交换机/防火墙等的网络设备做 SNMP 回退和 SSH/Telnet 采集，服务器不会走这条链路。
- **设备被标为离线**：离线设备不会参与采集。
- **版本命令无输出**：`display version` / `show version` 执行失败或无输出时，会打印「版本命令无输出，无法识别厂商」并跳过，不会回写厂商也不会写 Redis。

按上面顺序：先看日志是否出现「开始 SSH/Telnet 采集」和「SSH 采集已写缓存」，再查设备列表厂商、前端列表，就能确认是否触发了「厂商自查 → 回写厂商 → 写内存缓存 → 前端按 IP 展示」的完整流程。

## 五、采集了但没数据（前端 CPU/内存显示 — 或空白）

可能原因与对应处理：

1. **已采集但解析失败**  
   - 日志里能看到「SSH 采集已写缓存 … cpu=-% memory=-%」或「SSH 采集解析失败：CPU/内存命令输出未匹配到数值」。  
   - 说明厂商自查和写缓存都执行了，但 CPU/内存命令的输出和当前正则不匹配。  
   - 现在接口会按 IP 返回该设备并带上「最后采集时间」，前端会显示该行且 CPU/内存为「—」。  
   - **处理**：核对设备实际命令输出（如锐捷 `show cpu` 输出「CPU utilization in five seconds: 2.80%」、`show memory` 输出「74.9% used rate」），在 `DeviceSshCollectService` 里为对应厂商增加或调整解析正则。

2. **SSH/Telnet 采集未执行（缓存无该 IP）**  
   - 当前 SSH/Telnet 采集**只写内存缓存、不写 Redis**。若该设备从未被采集或应用刚重启，缓存为空。  
   - 检查是否 `ssh-collect.enabled=true`、设备是否配置 SSH/Telnet 用户名密码、类型为网络设备且在线；再看日志是否有「开始 SSH/Telnet 采集」和「SSH 采集已写缓存」。

3. **数据来源优先级**  
   - GET /metrics/realtime 对网络设备**优先用 SSH/Telnet 内存缓存**（有则用），无则用 Redis（SNMP 等）。仅通过 SSH/Telnet 采集时无需 Redis。

## 六、点「排查网络设备」提示请求超时

说明前端在约 20 秒内没有收到后端响应，可按下面检查：

1. **后端是否已启动**  
   在运行前端的同一台机器上确认后端进程在跑（如 `java -jar` 或 IDE 里运行 NetPulse），默认端口 **8082**。

2. **浏览器直接访问接口**  
   在地址栏打开：  
   **http://localhost:8082/api/metrics/realtime/ssh-cache**  
   - 若能看到 JSON（如 `{"enabled":true,"cacheIps":[],"hint":"..."}`），说明后端正常，多半是前端代理或端口不对。  
   - 若打不开、一直转圈或连接被拒绝，说明后端未启动或未监听 8082。

3. **前端开发环境代理**  
   用 `npm run dev` 启动时，vite 会把 `/api` 代理到 `http://localhost:8082`（见 `vite.config.js` 的 `proxy`）。若后端改过端口，需把 `vite.config.js` 里 `proxy['/api'].target` 改成对应地址（如 `http://localhost:8080`）。

4. **生产/打包部署**  
   若访问的是打包后的页面（不是 dev），没有 vite 代理，需由 Nginx 等把 `/api` 转发到后端地址，或在前端配置里把请求 baseURL 指到后端完整地址。
