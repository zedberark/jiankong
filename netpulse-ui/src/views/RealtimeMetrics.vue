<template>
  <div class="page">
    <div class="toolbar">
      <span class="refresh-hint">每 {{ interval / 1000 }} 秒自动刷新</span>
      <button class="primary small" @click="throttledRefreshStats" :disabled="statsRefreshing">{{ statsRefreshing ? '刷新中…' : '刷新' }}</button>
    </div>
    <p v-if="lastRefreshMessage" class="last-refresh-msg">{{ lastRefreshMessage }}</p>
    <div v-if="networkDevicesAll.length" class="snmp-status-bar">
      <span class="snmp-status-item" :class="{ ok: snmpRedisOk, bad: !snmpRedisOk }">
        Redis 指标：{{ snmpRedisOk ? '已连接（可读取 SNMP 写入结果）' : '不可用（请配置 Redis 并避免排除 Redis 自动配置）' }}
      </span>
      <span class="snmp-status-item" :class="{ ok: snmpCollectEnabled, muted: !snmpCollectEnabled }">
        SNMP 采集服务：{{ snmpCollectEnabled ? '已启用（定时/刷新/单设备采集会写 Redis）' : '未启用（application 中 snmp.collect.enabled=true 并重启）' }}
      </span>
    </div>
    <div class="stats-row">
      <div class="stat-card stat-total">
        <div class="stat-accent"></div>
        <div class="stat-body">
          <div class="stat-head">
            <span class="stat-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4V6zm0 5h16v2H4v-2zm0 5h16v2H4v-2z"/></svg></span>
            <span class="stat-label">总设备数</span>
          </div>
          <div class="stat-value">{{ devices.length }}</div>
          <div class="stat-extra">共 {{ devices.length }} 台设备</div>
        </div>
      </div>
      <div class="stat-card stat-online">
        <div class="stat-accent"></div>
        <div class="stat-body">
          <div class="stat-head">
            <span class="stat-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg></span>
            <span class="stat-label">在线设备</span>
          </div>
          <div class="stat-value">{{ groupOnline }}</div>
          <div class="stat-extra">正常运行</div>
        </div>
      </div>
      <div class="stat-card stat-offline">
        <div class="stat-accent"></div>
        <div class="stat-body">
          <div class="stat-head">
            <span class="stat-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm3.59 5L12 10.59 8.41 7 7 8.41 10.59 12 7 15.59 8.41 17 12 13.41 15.59 17 17 15.59 13.41 12 17 8.41z"/></svg></span>
            <span class="stat-label">离线设备</span>
          </div>
          <div class="stat-value">{{ groupOffline }}</div>
          <div class="stat-extra">{{ groupOffline }} 台离线</div>
        </div>
      </div>
      <div class="stat-card stat-alert">
        <div class="stat-accent"></div>
        <div class="stat-body">
          <div class="stat-head">
            <span class="stat-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg></span>
            <span class="stat-label">未处理告警</span>
          </div>
          <div class="stat-value">{{ firingTotal }}</div>
          <div class="stat-extra">待处理</div>
        </div>
      </div>
    </div>
    <div class="card table-wrapper table-loading-wrap">
      <div v-if="statsRefreshing" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">刷新中…</p>
        </div>
      </div>
      <h3 class="table-heading">Linux 设备（CPU / 内存 / 磁盘）</h3>
      <table>
        <thead>
          <tr>
            <th>名称</th>
            <th class="th-sortable" @click="toggleSort('ip')">IP <span class="sort-arrow">{{ sortArrow('ip') }}</span></th>
            <th class="th-sortable" @click="toggleSort('type')">类型 <span class="sort-arrow">{{ sortArrow('type') }}</span></th>
            <th class="th-sortable" @click="toggleSort('cpu')">CPU <span class="sort-arrow">{{ sortArrow('cpu') }}</span></th>
            <th class="th-sortable" @click="toggleSort('memory')">内存 <span class="sort-arrow">{{ sortArrow('memory') }}</span></th>
            <th class="th-sortable" @click="toggleSort('disk')">磁盘 <span class="sort-arrow">{{ sortArrow('disk') }}</span></th>
            <th>最后采集时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in sortedLinuxDevices" :key="'linux-' + d.id">
            <td>{{ d.name }}</td>
            <td>{{ d.ip }}</td>
            <td>{{ typeLabel(d.type) }}</td>
            <td class="metric-cell"><span :class="metricClass(stats[d.id]?.cpuPercent)">{{ formatPercent(stats[d.id]?.cpuPercent) }}</span></td>
            <td class="metric-cell"><span :class="metricClass(stats[d.id]?.memoryPercent)">{{ formatPercent(stats[d.id]?.memoryPercent) }}</span></td>
            <td class="metric-cell"><span :class="metricClass(stats[d.id]?.diskPercent)">{{ formatPercent(stats[d.id]?.diskPercent) }}</span></td>
            <td class="stats-time" :class="{ 'stats-time-stale': d.status !== 'offline' && isStatsStale(stats[d.id]) }">
              <template v-if="d.status === 'offline'">—</template>
              <template v-else>{{ statsTimeLabel(stats[d.id]) }} <span v-if="isStatsStale(stats[d.id])" class="stats-stale-hint">（较旧）</span></template>
            </td>
          </tr>
          <tr v-if="!sortedLinuxDevices.length"><td colspan="7" class="empty">暂无 Linux 设备数据。请到「设备管理」添加类型为「服务器」的设备并配置 SSH 用户名/密码（无需安装 Telegraf，将用系统自带命令采集 CPU/内存/磁盘）；点「刷新」触发采集。</td></tr>
        </tbody>
      </table>
    </div>
    <div class="card table-wrapper table-loading-wrap">
      <div v-if="statsRefreshing" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">刷新中…</p>
        </div>
      </div>
      <h3 class="table-heading">网络设备列表（SNMP 指标）</h3>
      <div v-if="networkWithSnmpConfig.length && !snmpRedisOk" class="table-hint table-hint-warn">
        当前无法连接 Redis，SNMP 采集结果无法展示。请启动 Redis 并检查 <code>spring.data.redis</code>，不要使用会排除 Redis 的 profile（如 local）除非已单独配置 SNMP 用 Redis。
      </div>
      <table>
        <thead>
          <tr>
            <th>名称</th>
            <th class="th-sortable" @click="toggleSort('ip')">IP <span class="sort-arrow">{{ sortArrow('ip') }}</span></th>
            <th class="th-sortable" @click="toggleSort('type')">类型 <span class="sort-arrow">{{ sortArrow('type') }}</span></th>
            <th>指标来源</th>
            <th class="th-sortable" @click="toggleSort('cpu')">CPU <span class="sort-arrow">{{ sortArrow('cpu') }}</span></th>
            <th class="th-sortable" @click="toggleSort('memory')">内存 <span class="sort-arrow">{{ sortArrow('memory') }}</span></th>
            <th>入流量</th>
            <th>出流量</th>
            <th>最后采集时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in sortedNetworkDevicesByType" :key="'snmp-' + d.id">
            <td>{{ d.name }} <span v-if="(d.status || '') === 'offline'" class="status-offline-tag">（离线）</span></td>
            <td>{{ d.ip }}</td>
            <td>{{ typeLabel(d.type) }}</td>
            <td class="metric-cell">
              <span v-if="(d.status || '') === 'offline'" class="metric-na">—</span>
              <span v-else :class="['src-badge', 'src-' + (statsSource[d.id] || 'na')]">{{ sourceLabel(statsSource[d.id]) }}</span>
            </td>
            <td class="metric-cell"><span :class="metricClass(stats[d.id]?.cpuPercent)">{{ (d.status || '') === 'offline' ? '—' : formatPercent(stats[d.id]?.cpuPercent) }}</span></td>
            <td class="metric-cell"><span :class="metricClass(stats[d.id]?.memoryPercent)">{{ (d.status || '') === 'offline' ? '—' : formatPercent(stats[d.id]?.memoryPercent) }}</span></td>
            <td class="metric-cell metric-traffic">{{ (d.status || '') === 'offline' ? '—' : formatOctets(stats[d.id]?.ifInOctetsTotal) }}</td>
            <td class="metric-cell metric-traffic">{{ (d.status || '') === 'offline' ? '—' : formatOctets(stats[d.id]?.ifOutOctetsTotal) }}</td>
            <td class="stats-time" :class="{ 'stats-time-stale': isStatsStale(stats[d.id]) }">
              <template v-if="(d.status || '') === 'offline'">—</template>
              <template v-else>{{ statsTimeLabel(stats[d.id]) }} <span v-if="isStatsStale(stats[d.id])" class="stats-stale-hint">（较旧）</span></template>
            </td>
          </tr>
          <tr v-if="!sortedNetworkDevicesByType.length"><td colspan="9" class="empty">暂无网络设备</td></tr>
        </tbody>
      </table>
    </div>
    <div class="card trend-section">
      <h3>监控趋势（CPU / 内存）<span class="badge-history">历史数据</span></h3>
      <div class="trend-toolbar">
        <span>主机</span>
        <select v-model="trendHost" class="filter-select">
          <option value="">请选择 Linux 设备</option>
          <option v-for="d in linuxDevicesForTrend" :key="d.id" :value="d.ip">{{ d.name }} ({{ d.ip }})</option>
        </select>
        <span>时间范围</span>
        <select v-model="trendRange" class="filter-select">
          <option value="1m">1 分钟（测试）</option>
          <option value="5m">5 分钟</option>
          <option :value="6">6 小时</option>
          <option :value="12">12 小时</option>
          <option :value="24">24 小时</option>
          <option :value="48">48 小时</option>
        </select>
        <button class="primary small" @click="loadTrend" :disabled="!trendHost || trendLoading">{{ trendLoading ? '查询中…' : '查询趋势' }}</button>
      </div>
      <div v-if="trendLoading" class="trend-loading">正在查询 InfluxDB，请稍候…（最多约 12 秒，超时请检查 InfluxDB 或刷新页面）</div>
      <div v-else-if="trendError" class="trend-error">{{ trendError }}</div>
      <div v-else-if="trendLoaded" class="charts-row">
        <div class="chart-wrap">
          <div ref="cpuChartRef" class="chart"></div>
          <div class="chart-title">CPU 使用率 %</div>
        </div>
        <div class="chart-wrap">
          <div ref="memChartRef" class="chart"></div>
          <div class="chart-title">内存使用率 %</div>
        </div>
      </div>
      <div v-else-if="trendRequested" class="trend-empty-wrap">
        <div class="charts-row charts-row-placeholder">
          <div class="chart-wrap">
            <div class="chart-placeholder">CPU 使用率 %<br/><span class="no-data">暂无数据</span></div>
            <div class="chart-title">CPU 使用率 %</div>
          </div>
          <div class="chart-wrap">
            <div class="chart-placeholder">内存使用率 %<br/><span class="no-data">暂无数据</span></div>
            <div class="chart-title">内存使用率 %</div>
          </div>
        </div>
        <div class="trend-empty">
          <template v-if="trendHint">{{ trendHint }}</template>
          <template v-else>
            暂无该主机的 Telegraf 历史数据（趋势图依赖 InfluxDB + Telegraf）。<br/>
            <strong>未安装 Telegraf</strong>时：在设备管理配置 SSH 后，上方「Linux 设备」表格会通过 Linux 自带命令（<code>free</code>、<code>/proc/stat</code>）显示实时 CPU/内存，仅趋势图无数据。<br/>
            若已安装 Telegraf，请确认：1）InfluxDB 已配置且可访问；2）设备「名称」与 Linux <code>hostname</code> 一致（或 Telegraf 的 host 设为设备 IP）；3）已配置 <code>[[inputs.cpu]]</code>、<code>[[inputs.mem]]</code> 并 <code>systemctl restart telegraf</code>，等待 1～2 分钟再查。
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, onActivated } from 'vue'
import * as echarts from 'echarts'
import { getRealtimeMetrics, refreshRealtimeStats, getMetricsTrend } from '../api/metrics'
import { getAlertSummary } from '../api/alerts'
import { getDevices } from '../api/device'
import { getApiErrorHint, isUnauthorizedError } from '../api/request'
import { formatTime } from '../utils/systemTimezone'
import { throttle } from '../utils/debounce'

const devices = ref([])
const summary = ref({ normal: 0, warning: 0, critical: 0, offline: 0 })
const alertSummary = ref({ criticalCount: 0, warningCount: 0, infoCount: 0 })
const stats = ref({})
const statsSource = ref({})  /* deviceId -> 'telegraf' | 'snmp' | 'ssh' */
const snmpRedisOk = ref(false)
const snmpCollectEnabled = ref(false)
const statsRefreshing = ref(false)
const lastRefreshMessage = ref('')
const sortBy = ref(null)
const sortOrder = ref('asc')

function sortDeviceList(list) {
  const st = stats.value
  if (!sortBy.value) return [...list]
  const key = sortBy.value
  const order = sortOrder.value === 'asc' ? 1 : -1
  const out = [...list]
  out.sort((a, b) => {
    let va, vb
    if (key === 'ip') {
      va = (a.ip ?? '').toString().trim()
      vb = (b.ip ?? '').toString().trim()
      return order * va.localeCompare(vb, undefined, { numeric: true })
    }
    if (key === 'type') {
      va = String(a.type ?? '')
      vb = String(b.type ?? '')
      return order * va.localeCompare(vb)
    }
    if (key === 'status') {
      va = String(a.status ?? '')
      vb = String(b.status ?? '')
      return order * va.localeCompare(vb)
    }
    if (key === 'cpu') {
      va = st[a.id]?.cpuPercent != null ? Number(st[a.id].cpuPercent) : -1
      vb = st[b.id]?.cpuPercent != null ? Number(st[b.id].cpuPercent) : -1
      return order * (va - vb)
    }
    if (key === 'memory') {
      va = st[a.id]?.memoryPercent != null ? Number(st[a.id].memoryPercent) : -1
      vb = st[b.id]?.memoryPercent != null ? Number(st[b.id].memoryPercent) : -1
      return order * (va - vb)
    }
    if (key === 'disk') {
      va = st[a.id]?.diskPercent != null ? Number(st[a.id].diskPercent) : -1
      vb = st[b.id]?.diskPercent != null ? Number(st[b.id].diskPercent) : -1
      return order * (va - vb)
    }
    return 0
  })
  return out
}

/** Linux 设备：类型为「服务器」的均为 Linux 设备，表格中展示以便无数据时也能看到并配置 SSH/刷新 */
const linuxDevices = computed(() =>
  devices.value.filter(d => normalizeType(d.type) === 'server')
)
/** Linux 设备表格只展示在线设备（离线不显示；无指标时仍显示该行，数值为 —） */
const linuxDevicesOnline = computed(() =>
  linuxDevices.value.filter(d => (d.status || '') !== 'offline')
)
/** 网络设备（非服务器）：仅显示在线，离线设备不在该表展示 */
const networkDevicesAll = computed(() =>
  devices.value.filter(d => normalizeType(d.type) !== 'server' && (d.status || '') !== 'offline')
)
/** 已填写 SNMP 社区名或 v3 凭据的网络设备（用于提示） */
const networkWithSnmpConfig = computed(() =>
  networkDevicesAll.value.filter(d => {
    const c = d.snmpCommunity != null && String(d.snmpCommunity).trim() !== ''
    const v3 = d.snmpSecurity != null && String(d.snmpSecurity).trim() !== ''
    return c || v3
  })
)
/** 当前在线设备数（normal + warning + critical），与首页仪表盘一致 */
const groupOnline = computed(() => {
  if (!devices.value?.length) return 0
  return devices.value.filter(d => ['normal', 'warning', 'critical'].includes(d.status || '')).length
})
/** 当前离线设备数，与首页仪表盘一致 */
const groupOffline = computed(() => {
  if (!devices.value?.length) return 0
  return devices.value.filter(d => (d.status || '') === 'offline').length
})
/** 未处理告警总数（严重+警告+一般），与首页仪表盘一致 */
const firingTotal = computed(() => {
  const s = alertSummary.value
  return (s.criticalCount || 0) + (s.warningCount || 0) + (s.infoCount || 0)
})
/** 监控趋势下拉仅展示在线的 Linux 设备（类型为 server 且非离线） */
const linuxDevicesForTrend = computed(() =>
  devices.value.filter(d => normalizeType(d.type) === 'server' && (d.status || '') !== 'offline')
)
const sortedLinuxDevices = computed(() => sortDeviceList(linuxDevicesOnline.value))
const sortedNetworkDevicesByType = computed(() => sortDeviceList(networkDevicesAll.value))

function toggleSort(field) {
  if (sortBy.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortBy.value = field
    sortOrder.value = 'asc'
  }
}

function sortArrow(field) {
  if (sortBy.value !== field) return '↕'
  return sortOrder.value === 'asc' ? '↑' : '↓'
}
const interval = 60000
let timer = null

const trendHost = ref('')
const trendRange = ref('24')  // '1m' | 6 | 12 | 24 | 48
const trendLoading = ref(false)
const trendLoaded = ref(false)
const trendRequested = ref(false)
const trendError = ref('')
const trendHint = ref('')
const cpuChartRef = ref(null)
const memChartRef = ref(null)
let cpuChart = null
let memChart = null
let lastRealtimeLoadErrorAt = 0
let realtimeAbortController = null

function notifyRealtimeErrorOnce(message) {
  const now = Date.now()
  if (now - lastRealtimeLoadErrorAt < 10000) return
  lastRealtimeLoadErrorAt = now
  alert(message)
}

function typeLabel(t) {
  const m = { server: '服务器', firewall: '防火墙', switch: '交换机', router: '路由器', other: '其他' }
  const key = normalizeType(t)
  return m[key] || key || '-'
}

/** 统一设备类型值：兼容字符串、枚举对象（含 toValue）与空值。 */
function normalizeType(type) {
  if (type == null) return ''
  if (typeof type === 'string') return type.trim().toLowerCase()
  if (typeof type?.toValue === 'function') {
    const v = type.toValue()
    return typeof v === 'string' ? v.trim().toLowerCase() : ''
  }
  return String(type).trim().toLowerCase()
}

function statsTimeLabel(s) {
  if (!s || !s.updatedAt) return '-'
  const sec = Math.floor((Date.now() - s.updatedAt) / 1000)
  // 前后端时钟存在几秒偏差时，避免出现“负数秒前”
  if (sec <= 0) return '刚刚'
  if (sec < 60) return sec + ' 秒前'
  if (sec < 3600) return Math.floor(sec / 60) + ' 分钟前'
  return formatTime(s.updatedAt)
}

function isStatsStale(s) {
  if (!s || !s.updatedAt) return false
  return (Date.now() - s.updatedAt) > 5 * 60 * 1000
}

function formatPercent(v) {
  if (v == null || v === undefined) return '-'
  const n = Number(v)
  if (Number.isNaN(n)) return '-'
  return n.toFixed(1) + '%'
}

function metricClass(v) {
  if (v == null || v === undefined) return 'metric-na'
  const n = Number(v)
  if (Number.isNaN(n)) return 'metric-na'
  if (n >= 90) return 'metric-high'
  if (n >= 70) return 'metric-mid'
  return 'metric-ok'
}

function sourceLabel(src) {
  if (src === 'snmp') return 'SNMP'
  if (src === 'ssh') return 'SSH'
  if (src === 'telegraf') return 'Linux'
  return '—'
}

function formatOctets(v) {
  if (v == null || v === '') return '—'
  const n = Number(String(v).replace(/,/g, ''))
  if (!Number.isFinite(n) || n < 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let x = n
  let u = 0
  while (x >= 1024 && u < units.length - 1) {
    x /= 1024
    u++
  }
  const shown = u === 0 ? String(Math.round(x)) : (x >= 100 ? x.toFixed(0) : x.toFixed(1))
  return shown + ' ' + units[u]
}

function load() {
  if (realtimeAbortController) realtimeAbortController.abort()
  realtimeAbortController = new AbortController()
  getRealtimeMetrics(realtimeAbortController.signal)
    .then(async (r) => {
      const realtimeDevices = Array.isArray(r?.data?.devices) ? r.data.devices : []
      let mergedDevices = realtimeDevices
      try {
        // 兜底：设备总表以 /devices 为准，避免实时接口偶发漏设备导致离线网络设备不显示。
        const listResp = await getDevices({})
        const allDevices = Array.isArray(listResp?.data) ? listResp.data : []
        if (allDevices.length > 0) {
          const byId = new Map(realtimeDevices.filter(d => d?.id != null).map(d => [d.id, d]))
          mergedDevices = allDevices.map(d => byId.get(d.id) || d)
        }
      } catch (_) {
        // /devices 兜底失败时保留实时接口返回，避免影响页面可用性
      }
      devices.value = mergedDevices
      summary.value = r.data && r.data.summary ? r.data.summary : {}
      stats.value = r.data && r.data.stats ? r.data.stats : {}
      statsSource.value = r.data && r.data.statsSource ? r.data.statsSource : {}
      snmpRedisOk.value = r.data && r.data.snmpEnabled === true
      snmpCollectEnabled.value = r.data && r.data.snmpCollectEnabled === true
      realtimeAbortController = null
    })
    .catch((err) => {
      realtimeAbortController = null
      devices.value = []
      summary.value = {}
      stats.value = {}
      statsSource.value = {}
      snmpRedisOk.value = false
      snmpCollectEnabled.value = false
      if (isUnauthorizedError(err)) return
      notifyRealtimeErrorOnce(getApiErrorHint(err, '实时指标加载失败，请检查网络或后端服务'))
    })
  getAlertSummary().then(r => {
    const data = r.data || {}
    alertSummary.value = {
      criticalCount: data.criticalCount ?? 0,
      warningCount: data.warningCount ?? 0,
      infoCount: data.infoCount ?? 0,
    }
  }).catch(() => { alertSummary.value = { criticalCount: 0, warningCount: 0, infoCount: 0 } })
}

function refreshStats() {
  statsRefreshing.value = true
  lastRefreshMessage.value = ''
  refreshRealtimeStats()
    .then((r) => {
      lastRefreshMessage.value = r.data?.message || '已刷新'
      load()
    })
    .catch(() => { lastRefreshMessage.value = '刷新请求失败' })
    .finally(() => { statsRefreshing.value = false })
}
/** 节流：防止连续点击刷新导致重复请求 */
const throttledRefreshStats = throttle(refreshStats, 1500)

let trendAbortController = null

function loadTrend() {
  if (!trendHost.value) return
  if (trendAbortController) trendAbortController.abort()
  trendAbortController = new AbortController()
  trendRequested.value = true
  trendLoading.value = true
  trendLoaded.value = false
  trendError.value = ''
  trendHint.value = ''
  const rangeVal = trendRange.value
  let hours = 24
  let minutes = undefined
  if (rangeVal === '1m') minutes = 1
  else if (rangeVal === '5m') minutes = 5
  else hours = Number(rangeVal)
  const timeoutId = setTimeout(() => {
    if (trendAbortController) trendAbortController.abort()
  }, 12000)
  getMetricsTrend(trendHost.value, hours, minutes, trendAbortController.signal, devices.value.find(d => d.ip === trendHost.value)?.name)
    .then(r => {
      clearTimeout(timeoutId)
      const data = r?.data || {}
      const cpu = (data.cpu || []).map(x => ({
        time: x.time,
        value: x.value != null ? Number(Number(x.value).toFixed(2)) : null
      }))
      const mem = (data.mem || []).map(x => ({
        time: x.time,
        value: x.value != null ? Number(Number(x.value).toFixed(2)) : null
      }))
      trendLoaded.value = cpu.length > 0 || mem.length > 0
      trendHint.value = data.hint || ''
      nextTick(() => {
        if (trendLoaded.value) {
          requestAnimationFrame(() => {
            renderCpuChart(cpu)
            renderMemChart(mem)
            setTimeout(() => {
              cpuChart?.resize()
              memChart?.resize()
            }, 80)
          })
        }
      })
    })
    .catch(e => {
      clearTimeout(timeoutId)
      trendLoaded.value = false
      const status = e.response?.status
      let msg
      if (e.code === 'ECONNABORTED' || e.message?.includes('timeout') || e.name === 'AbortError' || e.name === 'CanceledError') {
        msg = '请求超时（约 12 秒）。请检查后端 InfluxDB 是否已配置、可访问；或刷新页面后重试。'
      } else if (status === 401) {
        msg = '未授权(401)，请重新登录后再试。'
      } else {
        msg = e.response?.data?.message || e.message || '请求失败，请检查 InfluxDB 配置。'
      }
      trendError.value = msg
    })
    .finally(() => { clearTimeout(timeoutId); trendLoading.value = false; trendAbortController = null })
}

function renderCpuChart(cpu) {
  if (!cpuChartRef.value) return
  try { cpuChart?.dispose() } catch (_) {}
  cpuChart = echarts.init(cpuChartRef.value)
  const sorted = [...cpu].filter(x => x.time != null && x.value != null).sort((a, b) => (a.time || '').localeCompare(b.time || ''))
  const data = sorted.map(x => [new Date(x.time).getTime(), x.value])
  cpuChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'time',
      boundaryGap: false,
      axisLabel: {
        hideOverlap: true,
        formatter: (value) => {
          const d = new Date(value)
          return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        }
      }
    },
    yAxis: { type: 'value', name: '%', min: 0, max: 100 },
    series: [{ name: 'CPU 使用率', type: 'line', data, smooth: true }],
    dataZoom: [
      { type: 'slider', xAxisIndex: 0, height: 22, bottom: 0 },
      { type: 'inside', xAxisIndex: 0 }
    ],
    grid: { left: 50, right: 30, top: 20, bottom: 50 }
  }, { notMerge: true })
  cpuChart.resize()
}

function renderMemChart(mem) {
  if (!memChartRef.value) return
  try { memChart?.dispose() } catch (_) {}
  memChart = echarts.init(memChartRef.value)
  const sorted = [...mem].filter(x => x.time != null && x.value != null).sort((a, b) => (a.time || '').localeCompare(b.time || ''))
  const data = sorted.map(x => [new Date(x.time).getTime(), x.value])
  memChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'time',
      boundaryGap: false,
      axisLabel: {
        hideOverlap: true,
        formatter: (value) => {
          const d = new Date(value)
          return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        }
      }
    },
    yAxis: { type: 'value', name: '%', min: 0, max: 100 },
    series: [{ name: '内存使用率', type: 'line', data, smooth: true }],
    dataZoom: [
      { type: 'slider', xAxisIndex: 0, height: 22, bottom: 0 },
      { type: 'inside', xAxisIndex: 0 }
    ],
    grid: { left: 50, right: 30, top: 20, bottom: 50 }
  }, { notMerge: true })
  memChart.resize()
}

function onResize() {
  cpuChart?.resize()
  memChart?.resize()
}

onMounted(() => {
  load()
  timer = setInterval(load, interval)
  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
onActivated(() => {
  load()
})
function onVisibilityChange() {
  if (document.visibilityState === 'visible') load()
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1.25rem; flex-wrap: wrap; }
.refresh-hint { color: #64748b; font-size: 0.875rem; font-weight: 500; }
.last-refresh-msg { margin: 0.5rem 0 0 0; font-size: 0.8125rem; color: #059669; }
.stats-row { display: flex; flex-wrap: nowrap; gap: 1rem; margin-bottom: 1.5rem; }
.stat-card {
  position: relative;
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  transition: box-shadow 0.2s;
}
.stat-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
.stat-accent { width: 4px; flex-shrink: 0; background: #94a3b8; }
.stat-total .stat-accent { background: #0ea5e9; }
.stat-online .stat-accent { background: #10b981; }
.stat-offline .stat-accent { background: #f59e0b; }
.stat-alert .stat-accent { background: #ef4444; }
.stat-body { flex: 1; padding: 1rem 1.1rem; min-width: 0; }
.stat-head { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.stat-icon {
  width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
  border-radius: 8px; flex-shrink: 0;
}
.stat-icon svg { width: 18px; height: 18px; }
.stat-total .stat-icon { background: rgba(14, 165, 233, 0.15); color: #0ea5e9; }
.stat-online .stat-icon { background: rgba(16, 185, 129, 0.15); color: #10b981; }
.stat-offline .stat-icon { background: rgba(245, 158, 11, 0.15); color: #d97706; }
.stat-alert .stat-icon { background: rgba(239, 68, 68, 0.15); color: #ef4444; }
.stat-label { font-size: 0.8125rem; color: #64748b; font-weight: 500; }
.stat-value { font-size: 1.75rem; font-weight: 700; letter-spacing: -0.02em; color: #1e293b; line-height: 1.2; }
.stat-total .stat-value { color: #0c4a6e; }
.stat-online .stat-value { color: #059669; }
.stat-offline .stat-value { color: #b45309; }
.stat-alert .stat-value { color: #dc2626; }
.stat-extra { font-size: 0.75rem; color: #94a3b8; margin-top: 0.25rem; }
.metric-cell { font-variant-numeric: tabular-nums; font-weight: 500; }
.metric-ok { color: #059669; }
.metric-mid { color: #d97706; }
.metric-high { color: #dc2626; }
.metric-na { color: #94a3b8; }
.metric-traffic { font-size: 0.8125rem; color: #475569; }
.snmp-status-bar {
  display: flex; flex-wrap: wrap; gap: 0.75rem 1.25rem;
  margin-bottom: 1rem; padding: 0.65rem 1rem;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; font-size: 0.8125rem;
}
.snmp-status-item.ok { color: #047857; font-weight: 500; }
.snmp-status-item.bad { color: #b45309; font-weight: 500; }
.snmp-status-item.muted { color: #64748b; }
.src-badge { display: inline-block; padding: 0.15rem 0.45rem; border-radius: 6px; font-size: 0.75rem; font-weight: 600; }
.src-snmp { background: #dbeafe; color: #1d4ed8; }
.src-ssh { background: #fef3c7; color: #b45309; }
.src-telegraf { background: #e0e7ff; color: #4338ca; }
.src-na { background: #f1f5f9; color: #94a3b8; font-weight: 500; }
.table-hint-warn { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
.stats-time { font-size: 0.8125rem; color: #64748b; }
.stats-time-stale { color: #b45309; }
.stats-stale-hint { display: block; font-size: 0.75rem; color: #92400e; margin-top: 0.15rem; }
.hint-text { font-size: 0.8125rem; color: #64748b; margin-left: 0.5rem; }
.empty { text-align: center; color: #94a3b8; padding: 1.5rem; font-size: 0.875rem; }
.table-heading { margin: 0 0 0.25rem; font-size: 1rem; font-weight: 600; color: #1e293b; }
.table-desc { margin: 0 0 0.75rem; font-size: 0.875rem; color: #64748b; }
.table-desc code { background: #f1f5f9; padding: 0.15rem 0.4rem; border-radius: 4px; font-size: 0.8125rem; }
.table-hint { margin: 0 0 0.75rem; padding: 0.65rem 1rem; background: #eff6ff; border-radius: 8px; font-size: 0.875rem; color: #1e40af; }
.table-hint a { color: #2563eb; font-weight: 500; }
.status-offline-tag { font-size: 0.75rem; color: #94a3b8; font-weight: normal; }
.table-wrapper + .table-wrapper { margin-top: 1.5rem; }
.th-sortable { cursor: pointer; user-select: none; white-space: nowrap; }
.th-sortable:hover { background: #f1f5f9; }
.sort-arrow { margin-left: 0.25rem; font-size: 0.75rem; color: #94a3b8; }
.th-sortable:hover .sort-arrow { color: #475569; }
.col-ops { white-space: nowrap; }
.btn-collect { padding: 0.35rem 0.6rem; font-size: 0.75rem; border-radius: 6px; cursor: pointer; background: #f0f9ff; border: 1px solid #0ea5e9; color: #0284c7; }
.btn-collect:hover:not(:disabled) { background: #e0f2fe; }
.btn-collect:disabled { opacity: 0.7; cursor: not-allowed; }
.trend-section { margin-top: 1.5rem; }
.trend-section h3 { margin: 0 0 0.5rem 0; font-size: 1rem; font-weight: 600; color: #1e293b; }
.trend-desc { margin: 0 0 0.75rem; font-size: 0.8125rem; color: #64748b; line-height: 1.5; }
.badge-history { margin-left: 0.5rem; padding: 0.2rem 0.5rem; font-size: 0.75rem; font-weight: 500; color: #64748b; background: #f1f5f9; border-radius: 6px; }
.trend-toolbar { display: flex; align-items: center; gap: 0.5rem 1rem; flex-wrap: wrap; }
.trend-toolbar .filter-select { min-width: 180px; padding: 0.4rem 0.6rem; border-radius: 8px; border: 1px solid #e2e8f0; }
.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1.25rem; margin-top: 1rem; }
@media (max-width: 900px) { .charts-row { grid-template-columns: 1fr; } }
.chart-wrap { position: relative; }
.chart { height: 260px; width: 100%; }
.chart-title { font-size: 0.8125rem; color: #64748b; margin-top: 0.35rem; }
.trend-loading { color: #0ea5e9; padding: 1rem 0; font-size: 0.875rem; }
.trend-error { color: #dc2626; padding: 1rem 0; font-size: 0.875rem; }
.chart-placeholder { min-height: 280px; display: flex; flex-direction: column; align-items: center; justify-content: center; background: #f8fafc; border: 1px dashed #e2e8f0; border-radius: 10px; color: #94a3b8; font-size: 0.875rem; }
.chart-placeholder .no-data { color: #64748b; font-weight: 500; margin-top: 0.25rem; }
.trend-empty-wrap .trend-empty { margin-top: 1rem; }
.trend-empty { color: #64748b; padding: 1rem 0; font-size: 0.875rem; line-height: 1.6; }
.trend-empty code { background: #f1f5f9; padding: 0.15rem 0.4rem; border-radius: 6px; font-size: 0.8125rem; }
</style>
