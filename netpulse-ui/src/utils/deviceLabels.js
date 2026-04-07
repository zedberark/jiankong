/**
 * 设备类型、设备状态、告警严重程度等文案映射，供多页面复用
 */

const DEVICE_TYPE_MAP = {
  server: '服务器',
  firewall: '防火墙',
  switch: '交换机',
  router: '路由器',
  other: '其他',
}

const DEVICE_STATUS_MAP = {
  normal: '在线',
  warning: '告警',
  critical: '告警',
  offline: '离线',
}

const ALERT_SEVERITY_MAP = {
  critical: '严重',
  warning: '警告',
  info: '一般/提示',
}

/** 设备类型显示文案 */
export function typeLabel(type) {
  if (type == null) return ''
  const t = typeof type === 'string' ? type : (type?.toValue?.() || type)
  return DEVICE_TYPE_MAP[t] || t || '其他'
}

/** 设备状态显示文案（列表/表格用：在线、告警、离线） */
export function deviceStatusText(status) {
  return DEVICE_STATUS_MAP[status] || '在线'
}

/** 设备状态显示文案（拓扑等用：正常、告警、严重、离线） */
export function deviceStatusLabel(status) {
  const m = { normal: '在线', warning: '告警', critical: '严重', offline: '离线' }
  return m[status] || status || '在线'
}

/** 告警严重程度显示文案 */
export function severityLabel(severity) {
  return ALERT_SEVERITY_MAP[severity] || severity || '-'
}
