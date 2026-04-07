/**
 * 告警相关 API：规则增删改查、告警历史、汇总、模板、通知渠道配置。
 */
import request from './request'

/** 告警规则列表 */
export function getAlertRules() {
  return request.get('/alerts/rules')
}

/** 新增告警规则（指标条件、设备上下线、邮件通知、自动修复等） */
export function createAlertRule(data) {
  return request.post('/alerts/rules', data)
}

/** 更新告警规则 */
export function updateAlertRule(id, data) {
  return request.put(`/alerts/rules/${id}`, data)
}

/** 删除告警规则 */
export function deleteAlertRule(id) {
  return request.delete(`/alerts/rules/${id}`)
}

/** 告警历史列表（分页，可按状态、严重程度筛选） */
export function getAlertHistory(params = {}) {
  return request.get('/alerts/history', { params: { page: 0, size: 20, ...params } })
}

/** 告警汇总（未处理数量、最近告警）。可选 params.group 仅统计该分组内设备 */
export function getAlertSummary(params) {
  return request.get('/alerts/summary', { params: params || {} })
}

/** 将单条告警标记为已处理 */
export function resolveAlert(id) {
  return request.post(`/alerts/resolve/${id}`)
}

/** 批量标记告警历史为已处理，ids 为告警历史 id 数组 */
export function batchResolveAlerts(ids) {
  return request.post('/alerts/history/batch-resolve', { ids })
}

/** 告警模板列表（用于快速创建规则） */
export function getAlertTemplates() {
  return request.get('/alerts/templates')
}

/** 保存为告警模板 */
export function createAlertTemplate(data) {
  return request.post('/alerts/templates', data)
}

/** 告警通知渠道：邮箱配置 */
export function getAlertNotifySettings() {
  return request.get('/alerts/notify-settings')
}

export function saveAlertNotifySettings(data) {
  return request.put('/alerts/notify-settings', data)
}
