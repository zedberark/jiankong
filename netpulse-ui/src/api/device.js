/**
 * 设备管理相关 API：列表（可选分组）、分组列表、增删改、Ping、SNMP 采集、健康汇总。
 */
import request from './request'

/** 设备列表，params 可传 { group: '分组名' } */
export function getDevices(params) {
  return request.get('/devices', { params: params || {} })
}

/** 所有分组名称 */
export function getDeviceGroups() {
  return request.get('/devices/groups')
}

/** 新增设备（IP、类型、用户、密码等） */
export function createDevice(data) {
  return request.post('/devices', data)
}

/** 更新设备信息（部分字段留空则不覆盖，如密码） */
export function updateDevice(id, data) {
  return request.put(`/devices/${id}`, data)
}

/** 逻辑删除设备 */
export function deleteDevice(id) {
  return request.delete(`/devices/${id}`)
}

/** 对指定设备执行 Ping，返回 rttMs、status(up/down) */
export function pingDevice(id) {
  return request.get(`/devices/${id}/ping`)
}

/** 设备健康汇总：各状态数量（normal/warning/critical/offline） */
export function getDevicesHealth() {
  return request.get('/devices/health')
}

/** 立即刷新全部设备在线状态（触发后端一次可达性探测） */
export function refreshDeviceStatus() {
  return request.post('/devices/refresh-status', {})
}

/** 通过 SSH 获取 Linux 主机名并更新设备名称（仅类型为服务器的设备，需已配置 SSH 且在线）。须 POST 到 /api/devices/:id/fetch-hostname。 */
export function fetchDeviceHostname(id) {
  return request.post(`/devices/${id}/fetch-hostname`, {})
}

/** 对指定网络设备执行一次 SNMP 采集并写入 Redis（需后端 snmp.collect.enabled=true 且已配置 SNMP） */
export function collectDeviceSnmp(id) {
  return request.post(`/devices/${id}/collect-snmp`, {})
}
