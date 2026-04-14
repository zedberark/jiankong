<template>
  <div class="page batch-page">
    <div class="layout-4">
      <!-- 左上：Linux 智能运维命令生成 -->
      <section class="pane pane-tl">
        <h3 class="section-title">Linux 运维控制台</h3>
        <p class="hint">仅针对 <strong>Linux 服务器</strong>：选择常用脚本或命令模板，可配合 AI 运维助手优化命令后，在右侧终端中执行。</p>

        <div class="form-group quick-row">
          <div class="quick-col">
            <label>智能巡检脚本 <span class="label-hint">（Linux 一键健康检查，适合批量服务器）</span></label>
            <select v-model="selectedDailyCmd" @change="onDailyCommandSelect" class="quick-select">
              <option value="">-- 请选择巡检项 --</option>
              <option v-for="item in allDailyCommands" :key="item.cmd" :value="item.cmd">
                {{ item.label }}
              </option>
              <option value="__custom__">＋ 添加自定义巡检命令…</option>
            </select>
          </div>
          <div class="quick-col">
            <label>常用运维命令 <span class="label-hint">（单条命令，快速执行）</span></label>
            <select v-model="selectedQuickCmd" @change="onQuickCommandSelect" class="quick-select">
              <option value="">-- 请选择运维命令 --</option>
              <optgroup v-for="group in allCommandGroups" :key="group.category" :label="group.category">
                <option v-for="item in group.commands" :key="item.cmd" :value="item.cmd">
                  {{ item.label }}
                </option>
              </optgroup>
              <option value="__custom_quick__">＋ 添加自定义运维命令…</option>
            </select>
          </div>
        </div>
        <div class="form-group">
          <label>命令（可修改后执行）</label>
          <textarea v-model="command" rows="6" class="full-width" placeholder="例如：hostname 或 ls -la" />
        </div>
        <div class="actions">
          <button class="secondary" type="button" @click="optimizeCommand" :disabled="!command.trim() || optimizing">
            {{ optimizing ? 'AI 优化中…' : '用 AI 运维助手优化命令' }}
          </button>
          <button class="primary" type="button" @click="execute" :disabled="executing || !selectedIds.length || !command.trim()">
            {{ executing ? '执行中…' : '对选中服务器执行' }}
          </button>
        </div>
      </section>

      <!-- 右侧：终端（含 Linux 服务器选择） -->
      <section class="pane pane-tr table-loading-wrap">
        <div v-if="listLoading" class="table-loading-overlay">
          <div>
            <div class="loading-spinner" aria-hidden="true"></div>
            <p class="loading-text">加载中…</p>
          </div>
        </div>
        <h3 class="section-title">终端</h3>
        <div class="device-select-group">
          <div class="device-select-header">
            <label>分组</label>
            <select v-model="filterGroup" @change="debouncedLoad" class="group-select">
              <option value="">全部分组</option>
              <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
            </select>
            <label class="check-all" v-if="devices.length">
              <input type="checkbox" :checked="allSelected" @change="toggleAllDevices($event)" />
              全选
            </label>
            <span class="selected-count">已选 {{ selectedIds.length }}/{{ devices.length }}</span>
          </div>
          <div class="device-grid-wrap">
            <div v-if="devices.length" class="device-grid">
              <label v-for="d in devices" :key="d.id" class="device-card" :class="{ selected: selectedIds.includes(d.id) }">
                <input
                  type="checkbox"
                  class="device-card-check"
                  :checked="selectedIds.includes(d.id)"
                  @change="toggleDeviceSelection(d.id, $event)"
                />
                <div class="device-card-body">
                  <span class="device-card-name" :title="d.name">{{ d.name }}</span>
                  <span class="device-card-meta">
                    <span class="device-card-ip">{{ d.ip }}</span>
                    <span class="device-card-type">{{ typeLabel(d.type) }}</span>
                  </span>
                </div>
              </label>
            </div>
            <p v-else class="empty-devices">暂无 Linux 服务器，请到设备管理添加类型为「服务器」的设备并配置 SSH 账号。</p>
          </div>
        </div>
        <p class="hint">
          当前终端：
          <span v-if="currentServer">
            {{ currentServer.name || '未命名服务器' }}（{{ currentServer.ip || '无 IP' }}）
          </span>
          <span v-else>未连接，请在上方选择一台 Linux 服务器。</span>
          <button
            type="button"
            class="link-btn"
            v-if="currentDeviceId"
            @click="openTerminal"
          >
            连接终端
          </button>
          <button
            type="button"
            class="link-btn"
            v-if="terminalConnected"
            @click="disconnectTerminal"
          >
            断开终端
          </button>
        </p>
        <div class="terminal-wrap" :class="{ 'terminal-wrap-empty': !terminalConnected }">
          <div v-if="!currentDeviceId" class="terminal-placeholder">
            请在上方选择一台 Linux 服务器，并点击「连接终端」按钮后，这里会连接该服务器的 SSH 终端。
          </div>
          <div v-else ref="terminalRef" class="terminal-embed"></div>
        </div>
      </section>
    </div>
    <div v-if="executionHistory.length" class="card results-card">
      <h3>批量执行结果 <span class="hint">（不刷新页面时新结果会追加在下方）</span></h3>
      <div v-for="run in executionHistory" :key="run.id" class="run-block">
        <div class="run-header">{{ formatTime(run.time) }} · 命令: <code>{{ run.command }}</code></div>
        <div class="run-summary" v-if="run.results && run.results.length">
          <span class="summary-ok">成功 {{ (run.results.filter(r => r.success)).length }}</span>
          <span class="summary-err">失败 {{ (run.results.filter(r => !r.success)).length }}</span>
          <span class="summary-total">共 {{ run.results.length }} 台</span>
        </div>
        <div v-for="r in run.results" :key="run.id + '-' + r.deviceId" class="result-item" :class="r.success ? 'ok' : 'err'">
          <div class="result-title">===== {{ r.deviceName || ('设备#' + r.deviceId) }} ({{ r.success ? '成功' : '失败' }}) =====</div>
          <pre class="result-output">{{ formatBatchResult(r) }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { getDevices, getDeviceGroups } from '../api/device'
import { executeBatchCommand } from '../api/batch'
import { formatTime } from '../utils/systemTimezone'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { aiChat } from '../api/ai'
import { debounce } from '../utils/debounce'

const devices = ref([])
const listLoading = ref(false)
const groupList = ref([])
const filterGroup = ref('')
const selectedIds = ref([])
const command = ref('')
const selectedDailyCmd = ref('')
const selectedQuickCmd = ref('')
/** 每次执行的结果追加在此，不刷新页面则一直累积 */
const executionHistory = ref([])
const optimizing = ref(false)
const executing = ref(false)
const terminalConnected = ref(false)

const currentDeviceId = computed(() =>
  selectedIds.value && selectedIds.value.length ? selectedIds.value[0] : null
)

const currentServer = computed(() =>
  devices.value.find(d => d.id === currentDeviceId.value) || null
)
const allSelected = computed(() =>
  devices.value.length > 0 && selectedIds.value.length === devices.value.length
)

const terminalRef = ref(null)
let terminal = null
let fitAddon = null
let ws = null
let dataListener = null
let pasteContextHandler = null

// 智能巡检：一键执行多步骤健康检查，适合批量设备巡检
const builtinDailyCommands = [
  { label: '巡检：资源占用 TOP 进程（CPU/内存）', cmd: 'ps aux --sort=-%cpu | head -15' },
  { label: '巡检：Telegraf 采集器运行状态', cmd: 'systemctl status telegraf --no-pager 2>/dev/null || echo \"telegraf 未安装\"' },
  { label: '巡检：InfluxDB 容器与健康', cmd: 'docker ps | grep influx || echo \"无 influxdb 容器\"' },
  { label: '巡检：系统最近 100 条错误日志', cmd: 'journalctl -p err -n 100 --no-pager 2>/dev/null || grep -i \"error\" /var/log/syslog 2>/dev/null | tail -100' },
  { label: '巡检：定时任务 crontab 列表', cmd: 'crontab -l 2>/dev/null || echo \"当前用户无 crontab\"' },
  { label: '巡检：磁盘占用 TOP 目录（/var、/home）', cmd: 'du -h /var /home --max-depth=2 2>/dev/null | sort -h | tail -10' },
  { label: '巡检：最近 50 条登录记录', cmd: 'last -50 2>/dev/null || echo \"last 命令不可用\"' },
  { label: '巡检：系统负载 + TOP 5 进程', cmd: 'echo \"loadavg:\"; cat /proc/loadavg; echo; ps -eo pid,cmd,%cpu,%mem --sort=-%cpu | head -6' },
  { label: '巡检：常用端口 22/80/443/8080 监听情况', cmd: 'ss -tlnp 2>/dev/null | egrep \":22|:80|:443|:8080\" || netstat -tlnp 2>/dev/null | egrep \":22|:80|:443|:8080\"' },
  { label: '巡检：网络连通性（ping 默认网关）', cmd: 'ip route | awk \'/default/{print $3}\' | head -1 | xargs -r -I{} ping -c 3 {}' },
]
const customDailyCommands = ref([])
const allDailyCommands = computed(() => [...builtinDailyCommands, ...customDailyCommands.value])

// 常用运维命令：单条命令快速执行，按分类选择
const builtinCommandGroups = [
  {
    category: '基础信息',
    commands: [
      { label: '查看主机名', cmd: 'hostname' },
      { label: '查看运行时间与负载', cmd: 'uptime' },
      { label: '查看系统内核信息', cmd: 'uname -a' },
      { label: '查看当前时间', cmd: 'date' },
      { label: '查看系统负载', cmd: 'cat /proc/loadavg' },
      { label: '查看 CPU 核数', cmd: 'nproc' },
      { label: '查看系统版本', cmd: 'cat /etc/os-release 2>/dev/null' },
    ],
  },
  {
    category: '磁盘与内存',
    commands: [
      { label: '查看磁盘空间', cmd: 'df -h' },
      { label: '查看内存使用', cmd: 'free -m' },
      { label: '查看块设备与挂载', cmd: 'lsblk' },
      { label: '查看目录占用(/var/log /tmp /home)', cmd: 'du -sh /var/log /tmp /home 2>/dev/null' },
      { label: '查看磁盘 IO（需 sysstat）', cmd: 'iostat -x 1 1 2>/dev/null || echo "需安装 sysstat"' },
      { label: '查看内存与进程统计', cmd: 'vmstat 1 1' },
    ],
  },
  {
    category: '进程与服务',
    commands: [
      { label: '查看进程列表 TOP 25', cmd: 'ps aux | head -25' },
      { label: '查看运行中的 systemd 服务', cmd: 'systemctl list-units --type=service --state=running 2>/dev/null | head -20' },
      { label: '查看监听端口', cmd: 'ss -tlnp 2>/dev/null || netstat -tlnp' },
      { label: '查看当前连接概况', cmd: 'ss -an 2>/dev/null | head -20' },
      { label: '查看当前用户 crontab', cmd: 'crontab -l 2>/dev/null' },
    ],
  },
  {
    category: '网络',
    commands: [
      { label: '查看网卡与 IP', cmd: 'ip addr 2>/dev/null || ifconfig' },
      { label: '查看路由表', cmd: 'ip route 2>/dev/null || route -n' },
      { label: '本机回环 ping 测试', cmd: 'ping -c 2 127.0.0.1' },
      { label: '查看防火墙规则', cmd: 'iptables -L -n 2>/dev/null | head -15' },
      { label: '查看 DNS 配置', cmd: 'cat /etc/resolv.conf' },
    ],
  },
  {
    category: '系统与日志',
    commands: [
      { label: '查看 CPU 详细信息', cmd: 'lscpu | head -20' },
      { label: '查看环境变量数量', cmd: 'env | wc -l' },
      { label: '查看内核最近日志', cmd: 'dmesg | tail -15' },
      { label: '查看系统最近日志', cmd: 'journalctl -n 15 --no-pager 2>/dev/null || tail -15 /var/log/syslog 2>/dev/null' },
    ],
  },
  {
    category: '用户与登录',
    commands: [
      { label: '查看最近登录记录', cmd: 'last -5' },
      { label: '查看当前登录用户', cmd: 'who' },
      { label: '查看谁在操作及负载', cmd: 'w' },
    ],
  },
]
const customQuickCommands = ref([])
const allCommandGroups = computed(() => {
  if (!customQuickCommands.value.length) return builtinCommandGroups
  return [
    ...builtinCommandGroups,
    { category: '自定义命令（当前会话）', commands: customQuickCommands.value },
  ]
})

function typeLabel(t) {
  const m = { server: '服务器', switch: '交换机', router: '路由器', firewall: '防火墙', other: '其他' }
  return m[t] || t || '-'
}

function toggleDeviceSelection(deviceId, event) {
  const checked = event?.target?.checked === true
  if (checked) {
    if (!selectedIds.value.includes(deviceId)) {
      selectedIds.value = [...selectedIds.value, deviceId]
    }
  } else {
    selectedIds.value = selectedIds.value.filter(id => id !== deviceId)
  }
}

function toggleAllDevices(event) {
  const checked = event?.target?.checked === true
  selectedIds.value = checked ? devices.value.map(d => d.id) : []
}

function onQuickCommandSelect() {
  if (selectedQuickCmd.value === '__custom_quick__') {
    const label = window.prompt('请输入自定义运维命令名称（例如：重启某服务）:', '')
    if (!label) { selectedQuickCmd.value = ''; return }
    const cmd = window.prompt('请输入要执行的命令:', '')
    if (!cmd) { selectedQuickCmd.value = ''; return }
    const item = { label, cmd }
    customQuickCommands.value.push(item)
    selectedQuickCmd.value = cmd
    command.value = cmd
  } else if (selectedQuickCmd.value) {
    command.value = selectedQuickCmd.value
  }
}

function onDailyCommandSelect() {
  if (selectedDailyCmd.value === '__custom__') {
    // 弹出简单 prompt 让用户输入描述和命令，仅在当前页面会话内生效
    const label = window.prompt('请输入自定义巡检名称（例如：检查某业务日志）:', '')
    if (!label) { selectedDailyCmd.value = ''; return }
    const cmd = window.prompt('请输入要执行的脚本命令:', '')
    if (!cmd) { selectedDailyCmd.value = ''; return }
    const item = { label, cmd }
    customDailyCommands.value.push(item)
    selectedDailyCmd.value = cmd
    command.value = cmd
  } else if (selectedDailyCmd.value) {
    command.value = selectedDailyCmd.value
  }
}

function optimizeCommand() {
  const cmd = command.value.trim()
  if (!cmd || optimizing.value) return
  optimizing.value = true
  const prompt = `你是一名资深 Linux 运维工程师。请在保持语义不变的前提下，优化下面这条命令（或脚本片段）：\n\n${cmd}\n\n要求：\n1. 尽量考虑常见 Linux 发行版（如 CentOS、Ubuntu）。\n2. 保持严格的 shell 语法正确，可直接在 Bash 中执行。\n3. 如果原命令已经足够简洁，请只做必要的健壮性增强（例如增加 2>/dev/null 等），不要加入解释性文字。只输出优化后的命令本身。`
  aiChat(undefined, prompt, { transient: true })
    .then(r => {
      const data = r.data
      if (data && data.reply) {
        command.value = data.reply.trim()
      }
    })
    .catch(e => {
      alert(e.response?.data?.message || e.message || 'AI 优化命令失败，请检查 AI 配置')
    })
    .finally(() => { optimizing.value = false })
}

function getWsUrl(deviceId) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = location.host
  return `${protocol}//${host}/api/ws/ssh?deviceId=${deviceId}`
}

function initTerminal() {
  if (!terminalRef.value || terminal) return
  terminal = new Terminal({
    cursorBlink: true,
    theme: { background: '#ffffff', foreground: '#111827', cursor: '#111827' },
    fontFamily: 'Consolas, "Courier New", monospace',
    fontSize: 14,
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.open(terminalRef.value)
  fitAddon.fit()
  terminal.attachCustomKeyEventHandler((ev) => {
    const key = String(ev.key || '').toLowerCase()
    const ctrlOrMeta = ev.ctrlKey || ev.metaKey
    if (!ctrlOrMeta) return true
    if (key === 'c') {
      const selected = terminal?.getSelection?.() || ''
      if (selected) {
        ev.preventDefault()
        navigator.clipboard?.writeText?.(selected).catch(() => {})
        terminal?.clearSelection?.()
        return false
      }
      return true
    }
    if (key === 'v') {
      ev.preventDefault()
      navigator.clipboard?.readText?.().then((text) => {
        if (!text) return
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(text)
      }).catch(() => {})
      return false
    }
    return true
  })
  if (!pasteContextHandler) {
    pasteContextHandler = (e) => {
      e.preventDefault()
      navigator.clipboard?.readText?.().then((text) => {
        if (!text) return
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(text)
      }).catch(() => {})
    }
  }
  terminalRef.value.addEventListener('contextmenu', pasteContextHandler)
}

function disconnectTerminal() {
  if (ws) {
    ws.close()
    ws = null
  }
  terminalConnected.value = false
}

function connectTerminal(deviceId) {
  if (!deviceId) return
  disconnectTerminal()
  initTerminal()
  const url = getWsUrl(deviceId)
  ws = new WebSocket(url)
  ws.onopen = () => {
    terminalConnected.value = true
    terminal?.writeln('\r\n已连接到服务器 ' + deviceId + '，可直接输入或粘贴命令执行。\r\n')
  }
  ws.onmessage = (ev) => {
    const data = typeof ev.data === 'string' ? ev.data : ''
    try {
      const j = JSON.parse(data)
      if (j.error) {
        terminal?.writeln('\r\n[错误] ' + j.error + '\r\n')
        return
      }
    } catch (_) {}
    terminal?.write(data)
  }
  ws.onerror = () => {
    terminal?.writeln('\r\n[错误] WebSocket 连接失败，请检查后端与 SSH 配置。\r\n')
  }
  ws.onclose = (ev) => {
    const hint =
      ev.code === 1006
        ? '（异常断开，多为代理/后端未转发 WebSocket 或 SSH 通道被设备关闭）'
        : ev.code === 1000
          ? ''
          : `（code=${ev.code}${ev.reason ? ' ' + ev.reason : ''}）`
    terminal?.writeln('\r\n连接已关闭。' + hint + '\r\n')
    terminalConnected.value = false
    ws = null
  }
  if (dataListener) {
    dataListener.dispose()
    dataListener = null
  }
  dataListener = terminal?.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(data)
  })
}

function handleResize() {
  fitAddon?.fit()
}

function openTerminal() {
  if (!currentDeviceId.value) {
    alert('请先在上方选择一台 Linux 服务器')
    return
  }
  connectTerminal(currentDeviceId.value)
}

function load() {
  listLoading.value = true
  getDevices(filterGroup.value ? { group: filterGroup.value } : {})
    .then(r => {
      const all = r.data != null ? r.data : []
      devices.value = all.filter(d => d.type === 'server' && d.status !== 'offline')
    })
    .catch(() => { devices.value = []; alert('设备列表加载失败，请检查网络或后端服务') })
    .finally(() => { listLoading.value = false })
  getDeviceGroups().then(r => { groupList.value = r.data || [] }).catch(() => {})
}
const debouncedLoad = debounce(load, 300)

function execute() {
  if (executing.value || !selectedIds.value.length || !command.value.trim()) return
  executing.value = true
  const cmd = command.value.trim()
  executeBatchCommand(selectedIds.value, cmd)
    .then(r => {
      executionHistory.value.push({
        id: Date.now(),
        time: new Date().toISOString(),
        command: cmd,
        results: r.data || [],
      })
    })
    .catch(e => {
      const msg = e.code === 'ECONNABORTED' || e.message?.includes('timeout')
        ? '请求超时。若选择了较多设备，请减少数量重试；或稍后重试。'
        : (e.response?.data?.message || e.message || '执行请求失败，请检查网络或稍后重试。')
      alert(msg)
    })
    .finally(() => { executing.value = false })
}

/** 批量执行结果格式化：按设备独立块展示，避免多设备输出混在一行。 */
function formatBatchResult(item) {
  if (!item) return '(无输出)'
  const text = item.success ? (item.output || '') : (item.error || '连接或执行异常')
  const val = String(text || '').trim()
  return val === '' ? '(无输出)' : val
}

onMounted(() => {
  load()
  nextTick(() => {
    window.addEventListener('resize', handleResize)
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  disconnectTerminal()
  if (dataListener) {
    dataListener.dispose()
    dataListener = null
  }
  if (terminalRef.value && pasteContextHandler) {
    terminalRef.value.removeEventListener('contextmenu', pasteContextHandler)
  }
  pasteContextHandler = null
  terminal?.dispose()
})

watch(currentDeviceId, (id, prev) => {
  if (!id) {
    disconnectTerminal()
    return
  }
  if (prev != null && id !== prev) {
    disconnectTerminal()
  }
})
</script>

<style scoped>
.batch-page { padding: 0; }
.layout-4 {
  display: grid;
  grid-template-columns: 1fr 1.3fr;
  grid-template-rows: auto;
  gap: 1.25rem;
}
.pane {
  border-radius: 10px;
  border: 1px solid #e5e7eb;
  padding: 0.75rem 0.9rem 0.9rem;
  background: #fff;
  display: flex;
  flex-direction: column;
}
.pane-tl {
  min-height: 220px;
  grid-column: 1;
  grid-row: 1;
}
.pane-tr {
  min-height: 260px;
  grid-column: 2;
  grid-row: 1;
}
.form-card h3, .results-card h3 { margin: 0 0 0.75rem; font-size: 1rem; }
.hint { color: #6b7280; font-size: 0.875rem; margin-bottom: 1rem; }
.form-group { margin-bottom: 1rem; }
.form-group label { display: block; margin-bottom: 0.35rem; font-size: 0.875rem; color: #374151; }
.label-hint { font-weight: normal; color: #9ca3af; font-size: 0.8125rem; }
.device-select-group { margin-bottom: 1rem; }
.device-select-header {
  display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.5rem; flex-wrap: wrap;
}
.group-select { padding: 0.25rem 0.5rem; border: 1px solid #e5e7eb; border-radius: 6px; font-size: 0.8125rem; }
.device-select-header > label:first-child { font-weight: 500; color: #374151; }
.check-all { cursor: pointer; display: inline-flex; align-items: center; gap: 0.35rem; font-size: 0.8125rem; color: #6b7280; font-weight: normal; }
.selected-count { font-size: 0.8125rem; color: #6b7280; }
.device-grid-wrap {
  border: 1px solid #e5e7eb; border-radius: 10px; background: #fafbfc; padding: 1rem;
  max-height: 260px; overflow-y: auto;
}
.device-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.75rem;
}
.device-card {
  display: flex; align-items: flex-start; gap: 0.6rem; padding: 0.65rem 0.85rem;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; cursor: pointer;
  transition: border-color 0.15s, background 0.15s; min-height: 52px; box-sizing: border-box;
}
.device-card:hover { border-color: #cbd5e1; background: #f8fafc; }
.device-card.selected { border-color: #2563eb; background: #eff6ff; }
.device-card-check { flex-shrink: 0; margin: 0.25rem 0 0 0; }
.device-card-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 0.35rem; }
.device-card-name { font-size: 0.875rem; font-weight: 500; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.device-card-meta { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.device-card-ip { font-size: 0.8125rem; font-family: ui-monospace, monospace; color: #64748b; }
.device-card-type { font-size: 0.75rem; color: #64748b; padding: 0.12rem 0.4rem; background: #f1f5f9; border-radius: 4px; }
.empty-devices { margin: 1rem; color: #94a3b8; font-size: 0.8125rem; text-align: center; }
.quick-row { display: flex; flex-wrap: wrap; gap: 1rem; align-items: flex-end; }
.quick-col { flex: 1 1 240px; min-width: 220px; }
.quick-select { width: 100%; max-width: 320px; padding: 0.5rem 0.75rem; font-size: 0.875rem; }
.full-width { width: 100%; max-width: 100%; }
.terminal-wrap {
  /* Linux 控制台终端铺满可用区域 */
  flex: 1;
  width: 100%;
  min-height: 560px;
  border-radius: 8px;
  border: 1px solid #1f2937;
  background: #020617;
  overflow: auto;
  display: flex;
  align-items: stretch;
  justify-content: center;
  scrollbar-width: thin;
  scrollbar-color: #475569 #0b1220;
}
.terminal-wrap.terminal-wrap-empty {
  border: 1px solid #e5e7eb;
  background: #ffffff;
  scrollbar-color: #cbd5e1 #f8fafc;
}
.terminal-embed {
  flex: 1;
  min-height: 980px;
  background: #020617;
}
.terminal-wrap::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}
.terminal-wrap::-webkit-scrollbar-track {
  background: #0b1220;
}
.terminal-wrap.terminal-wrap-empty::-webkit-scrollbar-track {
  background: #f8fafc;
}
.terminal-wrap::-webkit-scrollbar-thumb {
  background: #475569;
  border-radius: 999px;
}
.terminal-wrap.terminal-wrap-empty::-webkit-scrollbar-thumb {
  background: #cbd5e1;
}
.terminal-wrap::-webkit-scrollbar-thumb:hover {
  background: #64748b;
}
.terminal-wrap.terminal-wrap-empty::-webkit-scrollbar-thumb:hover {
  background: #94a3b8;
}
.terminal-placeholder {
  width: 100%;
  min-height: 560px;
  background: transparent;
  color: #9ca3af;
  font-size: 0.85rem;
  padding: 1rem;
  box-sizing: border-box;
}
.link-btn {
  margin-left: 0.5rem;
  font-size: 0.8rem;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #2563eb;
  cursor: pointer;
}
.link-btn:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.results-card { margin-top: 1.5rem; }
.results-card .hint { font-weight: normal; color: #9ca3af; font-size: 0.8125rem; }
.run-block { margin-bottom: 1.25rem; padding-bottom: 1rem; border-bottom: 1px solid #e5e7eb; }
.run-block:last-child { border-bottom: none; margin-bottom: 0; padding-bottom: 0; }
.run-header { font-size: 0.8125rem; color: #6b7280; margin-bottom: 0.5rem; }
.run-header code { background: #f3f4f6; padding: 0.15rem 0.4rem; border-radius: 4px; }
.run-summary { font-size: 0.8125rem; margin-bottom: 0.5rem; display: flex; gap: 1rem; flex-wrap: wrap; }
.run-summary .summary-ok { color: #059669; }
.run-summary .summary-err { color: #dc2626; }
.run-summary .summary-total { color: #6b7280; }
.result-item { padding: 0.65rem 0; border-bottom: 1px solid #f3f4f6; font-size: 0.875rem; }
.result-title { font-weight: 700; margin-bottom: 0.4rem; }
.result-output {
  margin: 0;
  padding: 0.55rem 0.65rem;
  border-radius: 8px;
  border: 1px dashed #e5e7eb;
  background: #fafafa;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.8125rem;
  line-height: 1.5;
}
.result-item.ok { color: #059669; }
.result-item.err { color: #dc2626; }
</style>
