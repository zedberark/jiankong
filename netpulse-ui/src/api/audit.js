/**
 * 操作审计 API：查询谁在何时对何对象做了何种操作，支持按用户、操作类型筛选与导出。
 */
import request from './request'

/** 审计日志列表（分页，可选 username、action 筛选） */
export function getAuditLogs(params = {}) {
  return request.get('/audit', { params: { page: 0, size: 20, ...params } })
}
