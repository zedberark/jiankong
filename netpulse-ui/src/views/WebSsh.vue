<template>
  <div class="ssh-page" :class="{ embed: isEmbed }">
    <div v-if="!isEmbed && error" class="error-banner">
      <span class="error-text">{{ error }}</span>
      <button type="button" class="error-dismiss" @click="error = ''">×</button>
    </div>
    <div v-if="!isEmbed" class="ssh-toolbar">
      <select v-model="selectedId" @change="reconnect">
        <option value="">选择设备</option>
        <option v-for="d in devices" :key="d.id" :value="d.id">{{ d.name }} ({{ d.ip }})</option>
      </select>
      <span v-if="selectedDevice" class="port-badge">端口 {{ selectedDevice.sshPort ?? 22 }} ({{ (selectedDevice.sshPort ?? 22) === 23 ? 'Telnet' : 'SSH' }})</span>
      <button v-if="connected" class="danger small" @click="disconnect">断开</button>
      <button v-else class="primary small" @click="connect" :disabled="!selectedId">连接</button>
      <span class="hint">按设备管理中的端口连接：均输出到本终端</span>
    </div>
    <div ref="terminalRef" class="terminal-container"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { getDevices } from '../api/device'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'

const route = useRoute()
const terminalRef = ref(null)
const devices = ref([])
const selectedId = ref(route.params.id ? Number(route.params.id) : '')
const connected = ref(false)
const error = ref('')
let terminal = null
let fitAddon = null
let ws = null

const isEmbed = computed(() => route.query.embed === '1' || route.query.embed === 'true')

const selectedDevice = computed(() => {
  if (!selectedId.value || !devices.value.length) return null
  return devices.value.find(d => d.id === selectedId.value) || null
})

function getWsUrl() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = location.host
  return `${protocol}//${host}/api/ws/ssh?deviceId=${selectedId.value}`
}

function initTerminal() {
  if (!terminalRef.value) return
  terminal = new Terminal({
    cursorBlink: true,
    theme: { background: '#fff', foreground: '#1a1a1a', cursor: '#1a1a1a' },
    fontFamily: 'Consolas, "Courier New", monospace',
    fontSize: 14,
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.open(terminalRef.value)
  fitAddon.fit()
  // 支持 Ctrl/Cmd + C/V 复制粘贴（含浏览器剪贴板）
  terminal.attachCustomKeyEventHandler((ev) => {
    const key = (ev.key || '').toLowerCase()
    const ctrlOrMeta = ev.ctrlKey || ev.metaKey
    if (!ctrlOrMeta) return true
    if (key === 'c') {
      const selected = terminal?.getSelection() || ''
      if (selected) {
        if (navigator.clipboard?.writeText) {
          navigator.clipboard.writeText(selected).catch(() => {})
        }
        ev.preventDefault()
        return false
      }
      // 无选区时保留 Ctrl+C 发送中断信号
      return true
    }
    if (key === 'v') {
      if (navigator.clipboard?.readText) {
        navigator.clipboard.readText().then((text) => {
          if (text && ws && ws.readyState === WebSocket.OPEN) ws.send(text)
        }).catch(() => {})
      }
      ev.preventDefault()
      return false
    }
    return true
  })
  // 兜底：原生粘贴事件（Shift+Insert/右键粘贴）
  terminal.textarea?.addEventListener('paste', (e) => {
    const text = e.clipboardData?.getData('text') || ''
    if (text && ws && ws.readyState === WebSocket.OPEN) ws.send(text)
    e.preventDefault()
  })
  // 右键直接粘贴，避免弹出默认菜单
  terminalRef.value.addEventListener('contextmenu', (e) => {
    e.preventDefault()
    if (!navigator.clipboard?.readText) return
    navigator.clipboard.readText().then((text) => {
      if (text && ws && ws.readyState === WebSocket.OPEN) ws.send(text)
    }).catch(() => {})
  })
  terminal.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(data)
  })
}

function connect() {
  if (!selectedId.value) return
  error.value = ''
  if (!terminal) initTerminal()
  const url = getWsUrl()
  ws = new WebSocket(url)
  ws.onopen = () => {
    connected.value = true
    error.value = ''
    const dev = selectedDevice.value
    const port = dev && dev.sshPort != null ? dev.sshPort : 22
    const protocol = port === 23 ? 'Telnet' : 'SSH'
    const addr = dev && dev.ip ? `${dev.ip}:${port}` : '设备'
    if (terminal) {
      terminal.writeln('')
      terminal.write(`已连接 ${addr} (${protocol})，请输入命令...\r\n`)
    }
  }
  ws.onmessage = (ev) => {
    const data = typeof ev.data === 'string' ? ev.data : ''
    try {
      const j = JSON.parse(data)
      if (j.error) { connected.value = false; error.value = j.error; return }
    } catch (_) {}
    if (terminal) terminal.write(data)
  }
  ws.onerror = () => {
    error.value = '连接失败。请检查：1) 后端服务已启动 2) 设备管理里已填用户名和密码 3) 端口正确（22=SSH，23=Telnet）4) 设备已开启 Telnet/SSH 且防火墙放行对应端口 5) 后端所在机器能访问设备 IP'
  }
  ws.onclose = (ev) => {
    connected.value = false
    ws = null
    if (ev.code !== 1000 && !error.value) {
      error.value = ev.reason || '连接已断开（' + (ev.code ? 'code ' + ev.code : '未知原因') + '）'
    }
  }
}

function disconnect() {
  if (ws) {
    ws.close()
    ws = null
  }
  connected.value = false
}

function reconnect() {
  disconnect()
  if (selectedId.value) connect()
}

function onResize() {
  fitAddon?.fit()
}

onMounted(() => {
  getDevices()
    .then(r => {
      const list = r.data != null ? r.data : []
      devices.value = list
      if (!selectedId.value && list.length) {
        selectedId.value = list[0].id
      }
      // 若通过 /ssh/:id 从设备列表或拓扑跳转过来，自动发起连接
      if (selectedId.value && route.params.id) {
        connect()
      }
    })
    .catch(() => { devices.value = []; alert('设备列表加载失败，请检查网络或后端服务') })
  nextTick(() => initTerminal())
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  disconnect()
  terminal?.dispose()
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.ssh-page { display: flex; flex-direction: column; height: calc(100vh - 120px); min-height: 400px; }
.ssh-page.embed { height: 100%; min-height: 0; }
.ssh-toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; }
.ssh-toolbar select { min-width: 220px; }
.terminal-container { flex: 1; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.75rem; min-height: 360px; }
.ssh-page.embed .terminal-container { border-radius: 0; border: none; padding: 0.25rem; min-height: 0; }
.terminal-container :deep(.xterm) { height: 100%; }
.terminal-container :deep(.xterm-viewport) { background: #fff !important; }
.hint { color: #6b7280; font-size: 0.8125rem; margin-left: 0.5rem; }
.port-badge { font-size: 0.8125rem; color: #475569; padding: 0.25rem 0.5rem; background: #f1f5f9; border-radius: 6px; }
.error-banner {
  display: flex; align-items: center; justify-content: space-between; gap: 0.75rem;
  padding: 0.75rem 1rem; margin-bottom: 0.75rem; border-radius: 8px;
  background: #fef2f2; border: 1px solid #fecaca; color: #b91c1c;
}
.error-text { flex: 1; font-size: 0.875rem; }
.error-dismiss { background: none; border: none; color: #b91c1c; font-size: 1.25rem; cursor: pointer; padding: 0 0.25rem; line-height: 1; }
.error-dismiss:hover { color: #991b1b; }
</style>
