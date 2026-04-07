/**
 * 系统巡检：发起批量可达性探测、分页报告列表、报告明细。
 */
import request from './request'

/** 与 vite 代理 timeout 一致；设备多或离线多时后端可能数分钟才返回 */
const INSPECTION_RUN_TIMEOUT_MS = 600000

/** LLM 生成 AI 结论 */
const AI_SUMMARY_TIMEOUT_MS = 120000

/**
 * 执行一次巡检，group 为空表示全部设备（设备多时可能较慢，单独延长超时）
 * @param {string} group 分组名
 * @param {{ ai?: boolean }} options ai=true 时探测结束后调用千问/DeepSeek 生成结论（需系统设置中已配置 API）
 */
export function runInspection(group, options = {}) {
  const body = { group: group || '' }
  if (options.ai) body.ai = true
  return request.post('/inspection/run', body, { timeout: INSPECTION_RUN_TIMEOUT_MS })
}

/** 对已存在的报告生成或覆盖 AI 巡检结论（走 /ai/inspection-summary，与 /ai/chat 同前缀，避免部分环境将 /inspection/** 误判为静态资源） */
export function generateInspectionAiSummary(reportId) {
  return request.post('/ai/inspection-summary', { reportId }, { timeout: AI_SUMMARY_TIMEOUT_MS })
}

/** 分页报告列表；params 可含 source（MANUAL/HOURLY/…）、page、size */
export function getInspectionReports(params) {
  return request.get('/inspection/reports', { params: params || {} })
}

export function getInspectionReport(id) {
  return request.get(`/inspection/reports/${id}`)
}
