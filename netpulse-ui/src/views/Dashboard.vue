<template>
  <div class="dashboard">
    <div class="toolbar-row">
      <div class="toolbar-main">
        <div class="toolbar-title">网络运维监控中心</div>
        <div class="toolbar-meta">
          <span class="datetime">{{ currentDateTime }}</span>
          <span class="filter-label">分组</span>
          <select v-model="filterGroup" @change="debouncedLoad" class="filter-select">
            <option value="">全部</option>
            <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
          </select>
          <button class="primary small" @click="load" :disabled="loadRefreshing">{{ loadRefreshing ? '刷新中…' : '刷新' }}</button>
          <span v-if="lastLoadTime" class="update-hint">数据更新于 {{ lastLoadTime }}</span>
        </div>
      </div>
    </div>
    <div class="dashboard-content table-loading-wrap">
      <div v-if="loadRefreshing" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
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
    <div class="local-host-card">
      <div class="local-host-head">
        <h3>本机系统监控</h3>
        <span class="local-host-time">{{ localHostTime }}</span>
      </div>
      <div class="local-host-row">
        <div class="local-stat-card cpu">
          <div class="local-stat-icon"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 2v2H4v2H2v12h2v2h2v2h12v-2h2v-2h2V6h-2V4h-2V2H6zm12 4h2v12h-2v2H6v-2H4V6h2V4h12v2zM8 8v8h8V8H8zm2 2h4v4h-4v-4z"/></svg></div>
          <div class="local-stat-value">{{ localHost.cpuUsagePercent != null ? localHost.cpuUsagePercent + '%' : '--' }}</div>
          <div class="local-stat-label">CPU 使用率</div>
        </div>
        <div class="local-stat-card mem">
          <div class="local-stat-icon"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M2 6v12h4V6H2zm6 0v12h4V6H8zm6 0v12h4V6h-4zm6 0v12h4V6h-4z"/></svg></div>
          <div class="local-stat-value">{{ localHost.memoryUsagePercent != null ? localHost.memoryUsagePercent + '%' : '--' }}</div>
          <div class="local-stat-label">内存使用率</div>
          <div class="local-stat-detail" v-if="localHost.memoryUsedGb != null">{{ localHost.memoryUsedGb }} / {{ localHost.memoryTotalGb }} GB</div>
        </div>
        <div class="local-stat-card disk">
          <div class="local-stat-icon"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm0 2v16h12V4H6zm2 2h8v8H8V8zm2 2v4h4v-4h-4z"/></svg></div>
          <div class="local-stat-value">{{ localHost.diskUsagePercent != null ? localHost.diskUsagePercent + '%' : '--' }}</div>
          <div class="local-stat-label">磁盘使用率</div>
          <div class="local-stat-detail" v-if="localHost.diskUsedGb != null">{{ localHost.diskUsedGb }} / {{ localHost.diskTotalGb }} GB</div>
        </div>
        <div class="local-stat-card net">
          <div class="local-stat-icon"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2L2 7v2h2v6H2v2h2l8 5 8-5h2v-2h-2V9h2V7L12 2zm0 2.18l6 3.75v1.82H6V7.93l6-3.75zM4 9h16v6h-2v-4H6v4H4V9z"/></svg></div>
          <div class="local-stat-value">{{ networkMainText }}</div>
          <div class="local-stat-label">网络流量</div>
          <div class="local-stat-detail" v-if="localHost.networkUploadKbps != null">{{ formatKbps(localHost.networkUploadKbps) }} ↑ {{ formatKbps(localHost.networkDownloadKbps) }} ↓</div>
        </div>
      </div>
    </div>
    <div class="overview-row">
      <div class="card chart-card">
        <h3>设备状态概览（在线 / 离线）</h3>
        <div ref="chartRef" class="chart"></div>
      </div>
      <div class="card card-pie">
        <h3>设备类型分布</h3>
        <div ref="typePieRef" class="type-pie"></div>
      </div>
    </div>
    <div class="overview-row second-row">
      <div class="card alert-overview">
        <div class="alert-head">
          <h3>告警概况（一二三级）</h3>
          <button class="secondary small" @click="$router.push('/alerts')">查看详情</button>
        </div>
        <div class="alert-pie-wrap">
          <div ref="alertPieRef" class="alert-pie"></div>
          <div class="alert-legend">
            <div class="legend-row">
              <span class="legend-color critical"></span>
              <span>一级·严重</span>
              <span class="legend-count">{{ alertPiePercent('critical') }}%</span>
            </div>
            <div class="legend-row">
              <span class="legend-color warning"></span>
              <span>二级·警告</span>
              <span class="legend-count">{{ alertPiePercent('warning') }}%</span>
            </div>
            <div class="legend-row">
              <span class="legend-color info"></span>
              <span>三级·一般/提示</span>
              <span class="legend-count">{{ alertPiePercent('info') }}%</span>
            </div>
          </div>
        </div>
        <div class="alert-list-wrap">
          <div class="alert-subhead">最近告警</div>
          <div v-if="!recentAlerts.length" class="empty-alert">最近暂无告警</div>
          <ul v-else class="alert-list">
            <li v-for="a in recentAlerts" :key="a.id" class="alert-item">
              <div class="alert-main">
                <span :class="['badge', 'severity-' + a.severity]">{{ severityLabel(a.severity) }}</span>
                <span class="alert-msg">{{ a.message || ('规则 ' + a.ruleId + ' 触发') }}</span>
              </div>
              <div class="alert-meta">
                <span class="alert-time">{{ formatTime(a.startTime) }}</span>
                <span class="alert-device">设备ID: {{ a.deviceId }}</span>
              </div>
            </li>
          </ul>
        </div>
      </div>
      <div class="card offline-card">
        <div class="offline-head">
          <h3>离线设备</h3>
          <router-link v-if="offlineDevices.length > 0" to="/devices" class="link-more">查看全部</router-link>
        </div>
        <div v-if="!offlineDevices.length" class="empty-offline">当前无离线设备</div>
        <div v-else class="offline-table-wrap">
          <table class="offline-table">
            <thead>
              <tr>
                <th class="offline-th-name">设备名称</th>
                <th class="offline-th-ip">IP 地址</th>
                <th class="offline-th-ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in paginatedOfflineDevices" :key="d.id">
                <td class="offline-col-name"><span class="offline-name-text">{{ d.name }}</span></td>
                <td class="offline-col-ip"><code class="offline-ip-code">{{ d.ip }}</code></td>
                <td class="offline-col-ops"><router-link :to="'/devices'" class="offline-link">查看</router-link></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="offlineDevices.length > 0" class="offline-pagination">
          <span class="offline-pagination-info">共 {{ offlineDevices.length }} 条，每页 {{ OFFLINE_PAGE_SIZE }} 条，第 {{ offlinePage }} / {{ totalOfflinePages }} 页</span>
          <div class="offline-pagination-btns">
            <button type="button" class="op-btn" :disabled="offlinePage <= 1" @click="offlinePage = Math.max(1, offlinePage - 1)">上一页</button>
            <button type="button" class="op-btn" :disabled="offlinePage >= totalOfflinePages" @click="offlinePage = Math.min(totalOfflinePages, offlinePage + 1)">下一页</button>
          </div>
        </div>
      </div>
    </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 首页仪表盘：设备健康汇总、在线/离线时间线图表、设备类型饼图、告警摘要与最近告警、
 * 本机系统监控（CPU/内存/磁盘/网络）。数据每 10 秒刷新，离开页面时清理定时器与 ECharts 实例。
 */
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getDevices, getDevicesHealth, getDeviceGroups } from '../api/device'
import { getDeviceStatusTimeline, getLocalHostStats } from '../api/metrics'
import { getAlertSummary } from '../api/alerts'
import { getApiErrorHint, isUnauthorizedError } from '../api/request'
import { formatTime, getSystemTimezone } from '../utils/systemTimezone'
import { debounce } from '../utils/debounce'

// ---------- 数据状态 ----------
const devices = ref([])
const groupList = ref([])
const filterGroup = ref('')
const health = ref({ normal: 0, warning: 0, critical: 0, offline: 0 })
const recentAlerts = ref([])
const chartRef = ref(null)
const currentDateTime = ref('')
const lastLoadTime = ref('')
const timeline = ref({ labels: [], online: [], offline: [] })
const alertSummary = ref({ criticalCount: 0, warningCount: 0, infoCount: 0 })
const alertPieRef = ref(null)
const typePieRef = ref(null)
const localHost = ref({})
const localHostTime = ref('')
const loadRefreshing = ref(false)
let chart = null
let typePieChart = null
let resizeHandler = null

/** 当前分组内在线设备数（normal + warning + critical） */
const groupOnline = computed(() => {
  if (!devices.value || !devices.value.length) return 0
  return devices.value.filter(d => {
    const s = d.status || 'normal'
    return s === 'normal' || s === 'warning' || s === 'critical'
  }).length
})

/** 当前分组内离线设备数 */
const groupOffline = computed(() => {
  if (!devices.value || !devices.value.length) return 0
  return devices.value.filter(d => (d.status || 'offline') === 'offline').length
})

/** 设备类型分布，供类型饼图使用 */
const typePieData = computed(() => {
  const list = devices.value || []
  const m = { server: '服务器', firewall: '防火墙', switch: '交换机', router: '路由器', other: '其他' }
  const count = {}
  list.forEach(d => {
    const t = typeof d.type === 'string' ? d.type : (d.type?.toValue?.() || 'other')
    count[t] = (count[t] || 0) + 1
  })
  return Object.entries(count).map(([k, v]) => ({ name: m[k] || k, value: v }))
})

/** 离线设备列表：按离线时间排序，最近离线的在上面（lastPollTime 降序） */
const offlineDevices = computed(() => {
  const list = (devices.value || []).filter(d => (d.status || 'offline') === 'offline')
  return [...list].sort((a, b) => {
    const ta = a.lastPollTime ? new Date(a.lastPollTime).getTime() : 0
    const tb = b.lastPollTime ? new Date(b.lastPollTime).getTime() : 0
    return tb - ta
  })
})

const OFFLINE_PAGE_SIZE = 15
const offlinePage = ref(1)
const totalOfflinePages = computed(() =>
  Math.max(1, Math.ceil((offlineDevices.value?.length || 0) / OFFLINE_PAGE_SIZE))
)
/** 当前页的离线设备，每页 15 条 */
const paginatedOfflineDevices = computed(() => {
  const list = offlineDevices.value || []
  const start = (offlinePage.value - 1) * OFFLINE_PAGE_SIZE
  return list.slice(start, start + OFFLINE_PAGE_SIZE)
})

watch(totalOfflinePages, (total) => {
  if (offlinePage.value > total) offlinePage.value = 1
})

/** 告警总数（严重+警告+一般） */
const firingTotal = computed(() => {
  const s = alertSummary.value
  return (s.criticalCount || 0) + (s.warningCount || 0) + (s.infoCount || 0)
})

const networkMainText = computed(() => {
  const h = localHost.value
  const up = h.networkUploadKbps != null ? h.networkUploadKbps : 0
  const down = h.networkDownloadKbps != null ? h.networkDownloadKbps : 0
  const total = up + down
  if (total === 0 && h.cpuUsagePercent == null) return '--'
  return formatKbps(total) + '/s'
})

function formatKbps(kbps) {
  if (kbps == null) return '--'
  if (kbps >= 1024) return (Math.round((kbps / 1024) * 10) / 10) + ' MB/s'
  return (Math.round(kbps * 10) / 10) + ' KB/s'
}

/** 拉取本机系统监控（OSHI：CPU、内存、磁盘、网络），用于本机监控四卡片 */
function loadLocalHost() {
  getLocalHostStats()
    .then(r => {
      const d = r.data || {}
      localHost.value = d
      if (d.timestamp) {
        const t = new Date(d.timestamp)
        localHostTime.value = t.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
      }
    })
    .catch(() => { localHost.value = {} })
}

function alertPiePercent(key) {
  const s = alertSummary.value
  const total = (s.criticalCount || 0) + (s.warningCount || 0) + (s.infoCount || 0)
  if (!total) return '0'
  const n = key === 'critical' ? (s.criticalCount || 0) : key === 'warning' ? (s.warningCount || 0) : (s.infoCount || 0)
  return ((n / total) * 100).toFixed(1)
}
let alertPieChart = null
let clockTimer = null
let loadTimer = null
let localHostTimer = null
let lastLoadErrorAt = 0

function notifyLoadErrorOnce(message) {
  const now = Date.now()
  if (now - lastLoadErrorAt < 10000) return
  lastLoadErrorAt = now
  alert(message)
}

function updateClock() {
  const now = new Date()
  const tz = getSystemTimezone()
  currentDateTime.value = now.toLocaleString('zh-CN', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function typeLabel(t) {
  const m = { server: '服务器', firewall: '防火墙', switch: '交换机', router: '路由器', other: '其他' }
  return m[t] || t
}

function severityLabel(s) {
  const m = { critical: '严重', warning: '警告', info: '一般/提示' }
  return m[s] || s || '-'
}

/** 拉取设备列表、健康汇总、状态时间线、告警摘要等，并刷新折线图与类型饼图 */
function load() {
  loadRefreshing.value = true
  getDevices(filterGroup.value ? { group: filterGroup.value } : {})
    .then(r => {
      devices.value = r.data != null ? r.data : []
      lastLoadTime.value = formatTime(new Date(), { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      nextTick(updateTypePie)
    })
    .catch((err) => {
      devices.value = []
      if (isUnauthorizedError(err)) return
      notifyLoadErrorOnce(getApiErrorHint(err, '设备数据加载失败，请检查网络或后端服务'))
    })
    .finally(() => { loadRefreshing.value = false })
  getDeviceGroups().then(r => { groupList.value = r.data || [] }).catch(() => {})
  getDevicesHealth().then(r => {
    const data = r.data || {}
    health.value = {
      normal: data.normal ?? 0,
      warning: data.warning ?? 0,
      critical: data.critical ?? 0,
      offline: data.offline ?? 0,
    }
    updateChart()
  }).catch(() => {})
  getDeviceStatusTimeline(filterGroup.value ? { group: filterGroup.value } : {}).then(r => {
    const d = r.data || {}
    timeline.value = { labels: d.labels || [], online: d.online || [], offline: d.offline || [] }
    updateChart()
  }).catch(() => { updateChart() })
  const groupForAlert = filterGroup.value ? String(filterGroup.value).trim() : ''
  getAlertSummary(groupForAlert ? { group: groupForAlert } : {}).then(r => {
    const data = r.data || {}
    if (groupForAlert !== (filterGroup.value ? String(filterGroup.value).trim() : '')) return
    recentAlerts.value = data.recentHistory || []
    alertSummary.value = {
      criticalCount: data.criticalCount ?? 0,
      warningCount: data.warningCount ?? 0,
      infoCount: data.infoCount ?? 0,
    }
    updateAlertPie()
  }).catch(() => {
    if (groupForAlert !== (filterGroup.value ? String(filterGroup.value).trim() : '')) return
    recentAlerts.value = []
    alertSummary.value = { criticalCount: 0, warningCount: 0, infoCount: 0 }
    updateAlertPie()
  })
}
/** 分组筛选防抖，减少重复请求 */
const debouncedLoad = debounce(load, 300)

function updateChart() {
  if (!chart) return
  const labels = timeline.value.labels && timeline.value.labels.length ? timeline.value.labels : ['0:00']
  const online = timeline.value.online && timeline.value.online.length ? timeline.value.online : [(health.value.normal || 0) + (health.value.warning || 0) + (health.value.critical || 0)]
  const offline = timeline.value.offline && timeline.value.offline.length ? timeline.value.offline : [health.value.offline || 0]
  chart.setOption({
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: { interval: 0, rotate: labels.length > 12 ? 45 : 0 },
    },
    yAxis: { type: 'value', name: '设备数', min: 0 },
    tooltip: {
      trigger: 'axis',
      formatter: function (items) {
        if (!items || !items.length) return ''
        const time = items[0].axisValue
        const onItem = items.find(x => x.seriesName === '在线')
        const offItem = items.find(x => x.seriesName === '离线')
        const onVal = onItem ? onItem.value : 0
        const offVal = offItem ? offItem.value : 0
        return time + '<br/>在线: ' + onVal + ' 台<br/>离线: ' + offVal + ' 台'
      },
    },
    legend: { data: ['离线', '在线'], top: 0 },
    series: [
      {
        name: '离线',
        type: 'bar',
        data: offline,
        stack: 'status',
        itemStyle: { color: '#dc2626' },
        barMaxWidth: 24,
      },
      {
        name: '在线',
        type: 'bar',
        data: online,
        stack: 'status',
        itemStyle: { color: '#059669' },
        barMaxWidth: 24,
      },
    ],
    grid: { left: '10%', right: '6%', bottom: labels.length > 12 ? '18%' : '12%', top: '18%', containLabel: true },
  })
}

function initChart() {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  chart.setOption({
    xAxis: { type: 'category', data: [] },
    yAxis: { type: 'value', name: '设备数', min: 0 },
    legend: { data: ['离线', '在线'], top: 0 },
    series: [
      { name: '离线', type: 'bar', data: [], stack: 'status', itemStyle: { color: '#dc2626' }, barMaxWidth: 24 },
      { name: '在线', type: 'bar', data: [], stack: 'status', itemStyle: { color: '#059669' }, barMaxWidth: 24 },
    ],
    grid: { left: '10%', right: '6%', bottom: '12%', top: '18%', containLabel: true },
  })
  updateChart()
}

function initAlertPie() {
  if (!alertPieRef.value) return
  alertPieChart = echarts.init(alertPieRef.value)
  updateAlertPie()
}

function updateTypePie() {
  if (!typePieRef.value || !typePieChart) return
  const data = typePieData.value
  if (!data.length) {
    typePieChart.setOption({ series: [{ type: 'pie', data: [{ name: '暂无设备', value: 1, itemStyle: { color: '#e2e8f0' } }] }] })
    return
  }
  const colors = ['#0ea5e9', '#10b981', '#8b5cf6', '#f59e0b', '#94a3b8']
  typePieChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 台 ({d}%)' },
    legend: {
      right: 100,
      top: '18%',
      orient: 'vertical',
      type: 'scroll',
      itemGap: 10,
      textStyle: { fontSize: 16, fontWeight: 500 },
    },
    color: colors,
    series: [{ type: 'pie', radius: ['40%', '70%'], center: ['45%', '50%'], data, label: { formatter: '{b} {c}', fontSize: 12 } }],
  })
}

function initTypePie() {
  if (!typePieRef.value) return
  typePieChart = echarts.init(typePieRef.value)
  updateTypePie()
}

function updateAlertPie() {
  if (!alertPieChart) return
  const critical = alertSummary.value.criticalCount || 0
  const warning = alertSummary.value.warningCount || 0
  const info = alertSummary.value.infoCount || 0
  alertPieChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { show: false },
    series: [
      {
        name: '告警',
        type: 'pie',
        radius: ['45%', '75%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        data: [
          { value: critical, name: '一级·严重', itemStyle: { color: '#b91c1c' } },
          { value: warning, name: '二级·警告', itemStyle: { color: '#d97706' } },
          { value: info, name: '三级·一般/提示', itemStyle: { color: '#0369a1' } },
        ],
      },
    ],
  })
}

onMounted(() => {
  updateClock()
  clockTimer = setInterval(updateClock, 1000)       // 每秒更新一次时间
  load()
  loadLocalHost()
  localHostTimer = setInterval(loadLocalHost, 10000) // 本机监控每 10 秒刷新
  nextTick(() => {
    initChart()      // 在线/离线时间线折线图
    initAlertPie()   // 告警严重程度饼图
    initTypePie()    // 设备类型饼图
  })
  resizeHandler = () => { chart?.resize(); typePieChart?.resize(); alertPieChart?.resize() }
  window.addEventListener('resize', resizeHandler)
  loadTimer = setInterval(load, 20000)              // 设备/在线/离线/告警每 20 秒刷新，提高同步率
})
onUnmounted(() => {
  // 清理定时器与 ECharts 实例，避免离开页面后继续请求与内存泄漏
  if (clockTimer) clearInterval(clockTimer)
  if (loadTimer) clearInterval(loadTimer)
  if (localHostTimer) clearInterval(localHostTimer)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  try { chart?.dispose() } catch (_) {}
  try { typePieChart?.dispose() } catch (_) {}
  try { alertPieChart?.dispose() } catch (_) {}
})
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 1.5rem; }
.local-host-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 1rem 1.25rem;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.local-host-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem; }
.local-host-head h3 { margin: 0; font-size: 1rem; font-weight: 600; color: #1e293b; }
.local-host-time { font-size: 0.8125rem; color: #64748b; font-variant-numeric: tabular-nums; }
.local-host-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; }
@media (max-width: 900px) { .local-host-row { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 480px) { .local-host-row { grid-template-columns: 1fr; } }
.local-stat-card {
  display: flex; flex-direction: column; align-items: flex-start;
  padding: 1rem; border-radius: 10px; background: #f8fafc; border: 1px solid #e2e8f0;
}
.local-stat-card.cpu { border-left: 4px solid #3b82f6; }
.local-stat-card.mem { border-left: 4px solid #22c55e; }
.local-stat-card.disk { border-left: 4px solid #f59e0b; }
.local-stat-card.net { border-left: 4px solid #0ea5e9; }
.local-stat-icon { width: 28px; height: 28px; margin-bottom: 0.5rem; color: #64748b; }
.local-stat-card.cpu .local-stat-icon { color: #3b82f6; }
.local-stat-card.mem .local-stat-icon { color: #22c55e; }
.local-stat-card.disk .local-stat-icon { color: #f59e0b; }
.local-stat-card.net .local-stat-icon { color: #0ea5e9; }
.local-stat-icon svg { width: 100%; height: 100%; display: block; }
.local-stat-value { font-size: 1.5rem; font-weight: 700; color: #1e293b; letter-spacing: -0.02em; margin-bottom: 0.2rem; }
.local-stat-label { font-size: 0.8125rem; color: #64748b; font-weight: 500; }
.local-stat-detail { font-size: 0.75rem; color: #94a3b8; margin-top: 0.35rem; }
.stats-row { display: flex; flex-wrap: nowrap; gap: 1rem; }
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
.stat-accent {
  width: 4px;
  flex-shrink: 0;
  background: #94a3b8;
}
.stat-total .stat-accent { background: #0ea5e9; }
.stat-online .stat-accent { background: #10b981; }
.stat-offline .stat-accent { background: #f59e0b; }
.stat-alert .stat-accent { background: #ef4444; }
.stat-body { flex: 1; padding: 1rem 1.1rem; min-width: 0; }
.stat-head { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.stat-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  flex-shrink: 0;
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
.toolbar-row {
  margin-bottom: 0.75rem;
  padding: 0.85rem 1.25rem;
  border-radius: 14px;
  background: #ffffff;
  color: #0f172a;
  border: 1px solid #e2e8f0;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.04);
}
.toolbar-main { display: flex; flex-direction: column; gap: 0.35rem; }
.toolbar-title { font-size: 1rem; font-weight: 600; letter-spacing: 0.03em; color: #0f172a; }
.toolbar-meta { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; font-size: 0.8125rem; color: #475569; }
.datetime { font-weight: 500; color: #1f2937; }
.update-hint { font-size: 0.8125rem; color: #6b7280; }
.filter-label { font-size: 0.8125rem; color: #4b5563; font-weight: 500; }
.filter-select {
  padding: 0.3rem 0.6rem;
  border-radius: 9999px;
  border: 1px solid #d1d5db;
  font-size: 0.8125rem;
  background: #ffffff;
  color: #111827;
}
.filter-select option { color: #0f172a; }
.overview-row { display: grid; grid-template-columns: 1.1fr 1fr; gap: 1.25rem; align-items: stretch; }
.overview-row.second-row { grid-template-columns: 1fr 1fr; }
@media (max-width: 900px) { .overview-row, .overview-row.second-row { grid-template-columns: 1fr; } }
.chart-card h3, .alert-overview h3, .card-pie h3, .offline-card h3 { margin: 0 0 1rem; font-size: 1rem; font-weight: 600; color: #1e293b; }
.chart { height: 280px; }
.card-pie { min-height: 280px; }
.type-pie { height: 240px; width: 100%; }
.offline-card { display: flex; flex-direction: column; }
.offline-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.offline-head h3 { margin: 0; }
.link-more { font-size: 0.8125rem; color: #0ea5e9; }
.empty-offline { font-size: 0.875rem; color: #94a3b8; padding: 1rem 0; }
.offline-table-wrap { overflow-x: auto; }
.offline-table { border-collapse: collapse; width: 100%; min-width: 320px; font-size: 0.875rem; }
.offline-table th,
.offline-table td { padding: 0.6rem 1rem; text-align: left; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.offline-table th { font-weight: 600; color: #475569; background: #f8fafc; font-size: 0.8125rem; }
.offline-table tbody tr:hover td { background: #fafbfc; }
.offline-th-name { min-width: 120px; }
.offline-th-ip { min-width: 120px; }
.offline-th-ops { width: 72px; text-align: right; }
.offline-col-name { min-width: 120px; }
.offline-col-ip { min-width: 120px; }
.offline-col-ops { text-align: right; }
.offline-name-text { font-weight: 500; color: #334155; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; display: inline-block; }
.offline-ip-code { font-family: ui-monospace, monospace; font-size: 0.8125rem; color: #475569; background: #f1f5f9; padding: 0.2rem 0.45rem; border-radius: 4px; }
.offline-link { font-size: 0.8125rem; color: #0ea5e9; }
.offline-pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid #f1f5f9;
  font-size: 0.8125rem;
  color: #64748b;
}
.offline-pagination-btns { display: flex; gap: 0.4rem; }
.offline-pagination-btns .op-btn:disabled { opacity: 0.5; cursor: not-allowed; }
button.small { padding: 0.4rem 0.8rem; font-size: 0.8125rem; }
.alert-overview { position: relative; display: flex; flex-direction: column; gap: 0.5rem; }
.alert-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.alert-pie-wrap { display: flex; align-items: center; gap: 1rem; }
.alert-pie { width: 200px; height: 200px; }
.alert-legend { display: flex; flex-direction: column; gap: 0.5rem; font-size: 0.9375rem; }
.legend-row { display: flex; align-items: center; gap: 0.5rem; }
.legend-color { width: 12px; height: 12px; border-radius: 999px; display: inline-block; }
.legend-color.critical { background: #b91c1c; }
.legend-color.warning { background: #d97706; }
.legend-color.info { background: #0369a1; }
.legend-count { margin-left: 0.35rem; font-weight: 600; font-size: 1rem; color: #1e293b; }
.alert-list-wrap { margin-top: 0.35rem; }
.alert-subhead { font-size: 0.8125rem; color: #64748b; margin-bottom: 0.35rem; font-weight: 500; }
.alert-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.5rem; }
.alert-item { display: flex; flex-direction: column; gap: 0.2rem; padding: 0.5rem 0.75rem; border-radius: 10px; background: #f8fafc; border: 1px solid #f1f5f9; }
.alert-main { display: flex; align-items: center; gap: 0.5rem; }
.alert-msg { font-size: 0.875rem; color: #334155; }
.alert-meta { display: flex; justify-content: space-between; font-size: 0.75rem; color: #64748b; }
.empty-alert { font-size: 0.875rem; color: #94a3b8; }
.badge.severity-critical { background: #fee2e2; color: #b91c1c; }
.badge.severity-warning { background: #fef3c7; color: #b45309; }
.badge.severity-info { background: #e0f2fe; color: #0369a1; }
</style>
