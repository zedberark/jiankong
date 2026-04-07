/**
 * 导出与下载 API：告警历史、审计日志导出为 CSV 并触发浏览器下载。
 */
import request from './request'

/** 触发下载：告警历史 CSV（最近 500 条） */
export function exportAlertHistory() {
  request.get('/export/alert-history', { params: { size: 500 }, responseType: 'blob' })
    .then(r => {
      if (!r.data || !(r.data instanceof Blob)) {
        alert('导出失败：响应格式异常')
        return
      }
      const url = URL.createObjectURL(r.data)
      const a = document.createElement('a')
      a.href = url
      a.download = 'alert-history.csv'
      a.click()
      URL.revokeObjectURL(url)
    })
    .catch(() => alert('导出失败'))
}

/** 触发下载：审计日志 CSV（可选按用户名筛选，最近 500 条） */
export function exportAuditLog(username) {
  request.get('/export/audit-log', { params: { username: username || undefined, size: 500 }, responseType: 'blob' })
    .then(r => {
      if (!r.data || !(r.data instanceof Blob)) {
        alert('导出失败：响应格式异常')
        return
      }
      const url = URL.createObjectURL(r.data)
      const a = document.createElement('a')
      a.href = url
      a.download = 'audit-log.csv'
      a.click()
      URL.revokeObjectURL(url)
    })
    .catch(() => alert('导出失败'))
}
