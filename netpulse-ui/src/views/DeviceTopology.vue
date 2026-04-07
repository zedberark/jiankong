<template>
  <div class="page topology-page">
    <div class="toolbar">
      <span class="hint">拖拽设备可移动位置；默认不连线，可手动连线或删除线条</span>
      <label class="toolbar-label">
        分组
        <select v-model="filterGroup" @change="debouncedLoad" class="group-select">
          <option value="">全部</option>
          <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
        </select>
      </label>
      <label class="toolbar-label">
        状态
        <select v-model="statusFilter" class="group-select">
          <option value="">全部设备</option>
          <option value="online">仅在线设备</option>
          <option value="offline">仅离线设备</option>
        </select>
      </label>
      <button class="primary small" @click="load" :disabled="topologyLoading">{{ topologyLoading ? '刷新中…' : '刷新' }}</button>
      <button class="primary small" :class="{ active: connectMode }" @click="toggleConnectMode">
        {{ connectMode ? '退出连线' : '手动连线' }}
      </button>
      <button class="primary small" :class="{ active: removeLineMode }" @click="toggleRemoveLineMode" :disabled="!topologyLines.length">
        {{ removeLineMode ? '退出删线' : '删除线条' }}
      </button>
      <button class="primary small" @click="clearLines" :disabled="!edges.length">清空线条</button>
      <span v-if="connectMode" class="hint">
        {{ pendingNodeId ? '请选择第二个设备完成连线' : '请点击第一个设备开始连线' }}
      </span>
    </div>
    <div ref="canvasRef" class="topology-canvas table-loading-wrap">
      <div v-if="topologyLoading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <svg class="topology-lines" v-if="topologyLines.length" :width="lineCanvas.width" :height="lineCanvas.height" aria-hidden="true">
        <line
          v-for="(ln, idx) in topologyLines"
          :key="ln.key || ('ln-' + idx)"
          :x1="ln.x1"
          :y1="ln.y1"
          :x2="ln.x2"
          :y2="ln.y2"
          :class="['topology-line', ln.online ? 'online' : 'offline', { deletable: removeLineMode }]"
          @click.stop="removeLineByKey(ln.key)"
        />
      </svg>
      <div
        v-for="d in visibleNodes"
        :key="d.id"
        class="node"
        :class="[
          { offline: isOffline(d), dragging: draggingId === d.id },
          iconType(d.type) === 'server' ? 'node-server' : ''
        ]"
        :style="{ left: d.x + 'px', top: d.y + 'px' }"
        @mousedown.prevent="startDrag($event, d)"
      >
        <div class="node-icon" :class="iconClass(d.type)">
          <!-- Linux 服务器：机架 + 终端屏 + 状态灯 -->
          <svg v-if="iconType(d.type) === 'server'" viewBox="0 0 64 64" class="svg-icon svg-server">
            <defs>
              <linearGradient :id="'server-chassis-' + d.id" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#334155"/>
                <stop offset="100%" stop-color="#1e293b"/>
              </linearGradient>
              <linearGradient :id="'server-screen-' + d.id" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#0f172a"/>
                <stop offset="100%" stop-color="#1e293b"/>
              </linearGradient>
              <filter :id="'server-glow-' + d.id">
                <feGaussianBlur stdDeviation="0.8" result="blur"/>
                <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
              </filter>
            </defs>
            <!-- 机箱主体 -->
            <rect x="6" y="4" width="52" height="56" rx="4" :fill="'url(#server-chassis-' + d.id + ')'" stroke="#475569" stroke-width="1.2"/>
            <!-- 前面板槽位/通风条 -->
            <rect x="10" y="8" width="44" height="6" rx="1" fill="#0f172a" opacity="0.9"/>
            <rect x="10" y="18" width="44" height="6" rx="1" fill="#0f172a" opacity="0.7"/>
            <!-- 终端小屏 -->
            <rect x="12" y="28" width="40" height="18" rx="2" :fill="'url(#server-screen-' + d.id + ')'" stroke="#64748b" stroke-width="0.8"/>
            <text x="18" y="41" font-family="ui-monospace, monospace" font-size="8" fill="#22c55e">$</text>
            <line x1="18" y1="44" x2="44" y2="44" stroke="#475569" stroke-width="0.6"/>
            <!-- 状态灯 -->
            <circle cx="46" cy="52" r="2.5" :fill="isOffline(d) ? '#64748b' : '#22c55e'" :filter="'url(#server-glow-' + d.id + ')'"/>
            <circle cx="52" cy="52" r="2.5" fill="#3b82f6" opacity="0.9"/>
          </svg>
          <!-- 交换机：蓝色叠层矩形 + 左侧指示灯 -->
          <svg v-else-if="iconType(d.type) === 'switch'" viewBox="0 0 64 64" class="svg-icon icon-switch-svg">
            <rect x="10" y="12" width="44" height="16" rx="4" fill="currentColor"/>
            <circle cx="18" cy="20" r="2.5" fill="currentColor" opacity="0.4"/>
            <circle cx="18" cy="28" r="2.5" fill="currentColor" opacity="0.4"/>
            <rect x="10" y="36" width="44" height="16" rx="4" fill="currentColor"/>
            <circle cx="18" cy="44" r="2.5" fill="currentColor" opacity="0.4"/>
            <circle cx="18" cy="52" r="2.5" fill="currentColor" opacity="0.4"/>
          </svg>
          <!-- 路由器：绿色无线路由器，扁平底座 + 三天线 + 信号弧 -->
          <svg v-else-if="iconType(d.type) === 'router'" viewBox="0 0 64 64" class="svg-icon icon-router-svg">
            <rect x="12" y="38" width="40" height="16" rx="5" fill="currentColor"/>
            <line x1="20" y1="38" x2="18" y2="22" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>
            <circle cx="18" cy="20" r="2.5" fill="currentColor"/>
            <line x1="32" y1="38" x2="32" y2="26" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>
            <circle cx="32" cy="24" r="2.5" fill="currentColor"/>
            <line x1="44" y1="38" x2="46" y2="22" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/>
            <circle cx="46" cy="20" r="2.5" fill="currentColor"/>
            <rect x="20" y="42" width="3" height="2" rx="0.5" fill="currentColor" opacity="0.6"/>
            <rect x="26" y="42" width="3" height="2" rx="0.5" fill="currentColor" opacity="0.6"/>
            <rect x="32" y="42" width="3" height="2" rx="0.5" fill="currentColor" opacity="0.6"/>
            <rect x="38" y="42" width="3" height="2" rx="0.5" fill="currentColor" opacity="0.6"/>
            <path d="M24 28 Q32 20 40 28" stroke="currentColor" stroke-width="1.2" fill="none" opacity="0.85"/>
            <path d="M22 24 Q32 14 42 24" stroke="currentColor" stroke-width="1" fill="none" opacity="0.6"/>
            <path d="M26 32 Q32 26 38 32" stroke="currentColor" stroke-width="1" fill="none" opacity="0.5"/>
          </svg>
          <!-- 防火墙：橙色盾牌描边（无填充） -->
          <svg v-else-if="iconType(d.type) === 'firewall'" viewBox="0 0 64 64" class="svg-icon icon-firewall-svg">
            <path d="M32 6 L56 18 L56 32 C56 48 44 56 32 60 C20 56 8 48 8 32 L8 18 Z" stroke="currentColor" stroke-width="2.5" fill="none" stroke-linejoin="round"/>
          </svg>
          <!-- 其他 -->
          <svg v-else viewBox="0 0 64 64" class="svg-icon">
            <rect x="10" y="14" width="44" height="36" rx="3" fill="currentColor" opacity="0.9"/>
            <rect x="18" y="22" width="28" height="20" rx="1" fill="currentColor" opacity="0.4"/>
            <circle cx="32" cy="32" r="6" fill="currentColor"/>
          </svg>
        </div>
        <div class="node-badge">{{ deviceTypeBadgeLabel(d.type) }}</div>
        <div class="node-label">{{ d.name }}</div>
        <div class="node-ip">{{ d.ip }}</div>
        <div class="node-status" :class="isOffline(d) ? 'offline' : 'online'">
          {{ isOffline(d) ? '离线' : statusLabel(d.status) }}
        </div>
      </div>
      <div v-if="!visibleNodes.length" class="empty-tip">暂无设备，请到设备管理添加</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDevices, getDeviceGroups } from '../api/device'
import { debounce } from '../utils/debounce'

const STORAGE_KEY = 'netpulse_topology_positions'
const STORAGE_LINE_KEY = 'netpulse_topology_edges'
const NODE_WIDTH = 140
const NODE_HEIGHT = 160
const GRID_GAP = 80
const DRAG_THRESHOLD = 5

const router = useRouter()
const canvasRef = ref(null)
const nodes = ref([])
const groupList = ref([])
const filterGroup = ref('')
const statusFilter = ref('online')
const topologyLoading = ref(false)
const draggingId = ref(null)
const dragStart = ref({ x: 0, y: 0, left: 0, top: 0, moved: false })
const lineCanvas = ref({ width: 2400, height: 1400 })
const edges = ref([])
const connectMode = ref(false)
const removeLineMode = ref(false)
const pendingNodeId = ref(null)

function iconType(t) {
  if (!t) return 'other'
  const s = (t + '').toLowerCase()
  if (s === 'server') return 'server'
  if (s === 'switch' || s === 'sw') return 'switch'
  if (s === 'router') return 'router'
  if (s === 'firewall') return 'firewall'
  return 'other'
}

function iconClass(t) {
  return 'icon-' + iconType(t)
}

/** 右上角角标文案：服务器显示 Linux，其余显示设备类型名 */
function deviceTypeBadgeLabel(t) {
  const typ = iconType(t)
  const m = { server: 'Linux', switch: '交换机', router: '路由器', firewall: '防火墙', other: '其他' }
  return m[typ] || '其他'
}

function isOffline(d) {
  return d && (d.status === 'offline' || (d.status + '').toLowerCase() === 'offline')
}

const visibleNodes = computed(() => {
  if (!statusFilter.value) return nodes.value
  return nodes.value.filter(d =>
    statusFilter.value === 'offline' ? isOffline(d) : !isOffline(d)
  )
})

const topologyLines = computed(() => {
  const visibleMap = new Map(visibleNodes.value.map(n => [n.id, n]))
  if (!edges.value.length) return []
  const lines = []
  for (const e of edges.value) {
    const a = visibleMap.get(e.a)
    const b = visibleMap.get(e.b)
    if (!a || !b) continue
    lines.push({
      key: edgeKey(e.a, e.b),
      x1: a.x + NODE_WIDTH / 2,
      y1: a.y + NODE_HEIGHT / 2,
      x2: b.x + NODE_WIDTH / 2,
      y2: b.y + NODE_HEIGHT / 2,
      online: !isOffline(a) && !isOffline(b),
    })
  }
  return lines
})

function statusLabel(s) {
  const m = { normal: '在线', warning: '告警', critical: '严重', offline: '离线' }
  return m[s] || s || '在线'
}

function loadPositions() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch (e) {
    return {}
  }
}

function savePositions() {
  const map = {}
  nodes.value.forEach((n) => { map[n.id] = { x: n.x, y: n.y } })
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  } catch (_) { /* 忽略存储异常（如隐私模式） */ }
}

function loadEdges() {
  try {
    const raw = localStorage.getItem(STORAGE_LINE_KEY)
    const arr = raw ? JSON.parse(raw) : []
    if (!Array.isArray(arr)) return []
    return arr
      .map(e => ({ a: Number(e.a), b: Number(e.b) }))
      .filter(e => Number.isFinite(e.a) && Number.isFinite(e.b) && e.a !== e.b)
      .map(e => normalizeEdge(e.a, e.b))
  } catch (e) {
    return []
  }
}

function saveEdges() {
  try {
    localStorage.setItem(STORAGE_LINE_KEY, JSON.stringify(edges.value))
  } catch (_) {}
}

function normalizeEdge(a, b) {
  const x = Number(a)
  const y = Number(b)
  return x < y ? { a: x, b: y } : { a: y, b: x }
}

function edgeKey(a, b) {
  const n = normalizeEdge(a, b)
  return `${n.a}-${n.b}`
}

function toggleConnectMode() {
  connectMode.value = !connectMode.value
  if (connectMode.value) {
    removeLineMode.value = false
  }
  pendingNodeId.value = null
}

function toggleRemoveLineMode() {
  removeLineMode.value = !removeLineMode.value
  if (removeLineMode.value) {
    connectMode.value = false
    pendingNodeId.value = null
  }
}

function clearLines() {
  if (!edges.value.length) return
  if (!confirm('确定清空所有拓扑连线吗？')) return
  edges.value = []
  saveEdges()
}

function removeLineByKey(key) {
  if (!removeLineMode.value || !key) return
  const next = edges.value.filter(e => edgeKey(e.a, e.b) !== key)
  if (next.length === edges.value.length) return
  edges.value = next
  saveEdges()
}

function handleNodeClick(node) {
  if (!node) return
  if (connectMode.value) {
    if (!pendingNodeId.value) {
      pendingNodeId.value = node.id
      return
    }
    if (pendingNodeId.value === node.id) {
      pendingNodeId.value = null
      return
    }
    const n = normalizeEdge(pendingNodeId.value, node.id)
    const exists = edges.value.some(e => e.a === n.a && e.b === n.b)
    if (!exists) {
      edges.value = [...edges.value, n]
      saveEdges()
    }
    pendingNodeId.value = null
    return
  }
  router.push('/ssh/' + node.id)
}

function load() {
  topologyLoading.value = true
  getDevices(filterGroup.value ? { group: filterGroup.value } : {})
    .then((r) => {
      const list = r.data != null ? r.data : []
      const positions = loadPositions()
      const result = list.map((d, i) => {
        const pos = positions[d.id]
        let x, y
        if (pos && typeof pos.x === 'number' && typeof pos.y === 'number') {
          x = pos.x
          y = pos.y
        } else {
          const col = i % 4
          const row = Math.floor(i / 4)
          x = GRID_GAP + col * (NODE_WIDTH + GRID_GAP)
          y = GRID_GAP + row * (NODE_HEIGHT + GRID_GAP)
        }
        return { ...d, x, y }
      })
      nodes.value = result
      updateLineCanvas(result)
      // 只保留当前设备集合中可用的边，避免删设备后残留线条
      const idSet = new Set(result.map(n => n.id))
      edges.value = loadEdges().filter(e => idSet.has(e.a) && idSet.has(e.b))
      saveEdges()
    })
    .catch(() => { nodes.value = []; alert('设备列表加载失败，请检查网络或后端服务') })
    .finally(() => { topologyLoading.value = false })
  getDeviceGroups().then((r) => { groupList.value = r.data || [] }).catch(() => {})
}
const debouncedLoad = debounce(load, 300)

function startDrag(e, d) {
  draggingId.value = d.id
  dragStart.value = {
    x: e.clientX,
    y: e.clientY,
    left: d.x,
    top: d.y,
    moved: false,
  }
  document.addEventListener('mousemove', onDocMouseMove)
  document.addEventListener('mouseup', onDocMouseUp)
}

function onDocMouseMove(e) {
  if (draggingId.value == null) return
  const n = nodes.value.find((x) => x.id === draggingId.value)
  if (!n) return
  const { left, top, x, y } = dragStart.value
  const dx = e.clientX - x
  const dy = e.clientY - y
  if (!dragStart.value.moved && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
    dragStart.value.moved = true
  }
  n.x = left + dx
  n.y = top + dy
  updateLineCanvas(nodes.value)
}

function onDocMouseUp() {
  document.removeEventListener('mousemove', onDocMouseMove)
  document.removeEventListener('mouseup', onDocMouseUp)
  if (draggingId.value == null) return
  const n = nodes.value.find((x) => x.id === draggingId.value)
  const wasDrag = dragStart.value.moved
  draggingId.value = null
  if (wasDrag) {
    savePositions()
  } else if (n) {
    handleNodeClick(n)
  }
}

onMounted(load)

function updateLineCanvas(list) {
  const arr = Array.isArray(list) ? list : []
  if (!arr.length) {
    lineCanvas.value = { width: 2400, height: 1400 }
    return
  }
  let maxX = 0
  let maxY = 0
  arr.forEach((n) => {
    if (typeof n.x === 'number' && n.x > maxX) maxX = n.x
    if (typeof n.y === 'number' && n.y > maxY) maxY = n.y
  })
  lineCanvas.value = {
    width: Math.max(1200, Math.ceil(maxX + NODE_WIDTH + GRID_GAP)),
    height: Math.max(700, Math.ceil(maxY + NODE_HEIGHT + GRID_GAP)),
  }
}
</script>

<style scoped>
.topology-page { display: flex; flex-direction: column; height: calc(100vh - 120px); min-height: 400px; }
.toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; flex-shrink: 0; flex-wrap: wrap; }
.toolbar-label { display: inline-flex; align-items: center; gap: 0.4rem; font-size: 0.8125rem; color: #4b5563; }
.group-select { padding: 0.35rem 0.6rem; border: 1px solid #e5e7eb; border-radius: 6px; font-size: 0.8125rem; background: #fff; }
.hint { color: #6b7280; font-size: 0.875rem; }
.topology-canvas {
  flex: 1;
  position: relative;
  overflow: auto;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  min-height: 360px;
}
.topology-lines {
  position: absolute;
  inset: 0;
  pointer-events: auto;
  z-index: 1;
}
.topology-line {
  stroke-width: 2;
  stroke-linecap: round;
  opacity: 0.8;
  pointer-events: none;
}
.topology-line.online {
  stroke: #60a5fa;
}
.topology-line.offline {
  stroke: #cbd5e1;
  stroke-dasharray: 4 4;
}
.topology-line.deletable {
  pointer-events: auto;
  cursor: pointer;
  stroke-width: 3;
}
.topology-line.deletable:hover {
  stroke: #ef4444;
  stroke-dasharray: none;
}
.primary.small.active {
  background: #0f766e;
}
.node {
  position: absolute;
  z-index: 2;
  width: 120px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px;
  background: #fff;
  border: 2px solid #e5e7eb;
  border-radius: 12px;
  cursor: grab;
  transition: box-shadow 0.2s, border-color 0.2s;
  user-select: none;
}
.node:active { cursor: grabbing; }
.node.dragging {
  z-index: 10;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  border-color: #2563eb;
}
.node:hover {
  border-color: #93c5fd;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.12);
}
.node.offline { opacity: 0.85; }

/* Linux 服务器专用卡片 */
.node.node-server {
  width: 132px;
  padding: 12px 10px;
  background: linear-gradient(165deg, #1e293b 0%, #0f172a 100%);
  border: 1px solid #334155;
  border-radius: 14px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25), inset 0 1px 0 rgba(255, 255, 255, 0.06);
}
.node.node-server:hover {
  border-color: #475569;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.3), 0 0 0 1px rgba(34, 197, 94, 0.15), inset 0 1px 0 rgba(255, 255, 255, 0.06);
}
.node.node-server.dragging {
  border-color: #22c55e;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35), 0 0 0 2px rgba(34, 197, 94, 0.25);
}
.node.node-server .node-label { color: #f1f5f9; }
.node.node-server .node-ip { color: #94a3b8; }
.node.node-server .node-status.online { color: #22c55e; }
.node.node-server .node-status.offline { color: #64748b; }
/* 离线 Linux 服务器：白底，与其它离线设备一致 */
.node.node-server.offline {
  background: #fff;
  border-color: #e5e7eb;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.node.node-server.offline:hover { border-color: #d1d5db; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); }
.node.node-server.offline .node-label { color: #111827; }
.node.node-server.offline .node-ip { color: #6b7280; }
.node.node-server.offline .node-badge { color: #64748b; background: #f1f5f9; border-color: #e2e8f0; }
/* 所有设备右上角角标（通用） */
.node .node-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  font-size: 0.6rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #475569;
  background: #f1f5f9;
  padding: 0.2rem 0.45rem;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
}
/* Linux 服务器角标：绿色强调 */
.node.node-server .node-badge {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.15);
  border-color: rgba(34, 197, 94, 0.35);
}
.node-icon {
  width: 48px;
  height: 48px;
  color: #2563eb;
  margin-bottom: 0.35rem;
}
.node-icon.icon-server { color: transparent; }
.node-icon.icon-server .svg-server { width: 100%; height: 100%; display: block; }
.node-icon.icon-switch { color: #4f5d75; }
.node-icon.icon-router { color: #16a34a; }
.node-icon.icon-firewall { color: #ea580c; }
.node.offline .node-icon { color: #9ca3af; }
.node.offline .node-icon.icon-server .svg-server { opacity: 0.75; }
.svg-icon { width: 100%; height: 100%; display: block; }
.node-label { font-weight: 600; color: #111827; font-size: 0.8125rem; margin-bottom: 0.15rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
.node-ip { font-size: 0.7rem; color: #6b7280; margin-bottom: 0.2rem; }
.node-status { font-size: 0.7rem; font-weight: 500; }
.node-status.online { color: #059669; }
.node-status.offline { color: #9ca3af; }
.empty-tip { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); text-align: center; color: #9ca3af; }
</style>
