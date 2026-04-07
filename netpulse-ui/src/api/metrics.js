/**
 * 指标与监控 API：实时指标、趋势图、设备状态时间线、本机监控。
 */
import request from './request'

/** 指标汇总（up/down 等，供首页使用） */
export function getMetricsSummary() {
  return request.get('/metrics/summary')
}

/** 最近 24 小时按时间段的在线/离线设备数（首页柱状图）。可选 params.group 按分组过滤 */
export function getDeviceStatusTimeline(params) {
  return request.get('/metrics/device-status-timeline', { params: params || {} })
}

/** 实时指标：所有设备 CPU/内存、健康汇总、数据来源（Linux/网络设备） */
export function getRealtimeMetrics() {
  return request.get('/metrics/realtime')
}

/** 立即触发一次采集刷新（Linux 同步，网络设备异步），用于设备指标页「刷新」按钮 */
export function refreshRealtimeStats() {
  return request.post('/metrics/realtime/refresh-stats')
}

/** 监控趋势：Telegraf disk/net 时序。host 为设备 IP，hostName 为设备名称（用于 InfluxDB host=主机名 时的回退）。 */
export function getMetricsTrend(host, hours = 24, minutes, signal, hostName) {
  const params = { host }
  if (minutes != null && minutes > 0) params.minutes = minutes
  else params.hours = hours
  if (hostName) params.hostName = hostName
  return request.get('/metrics/trend', { params, timeout: 12000, signal })
}

/** 本机系统监控（运行后端的 Windows/Linux 主机）：CPU、内存、磁盘、网络 */
export function getLocalHostStats() {
  return request.get('/metrics/local-host')
}
