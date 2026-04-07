/**
 * 配置备份 API：设备/告警/系统配置的 JSON 备份与恢复。
 */
import request from './request'

/** 备份列表（含创建时间、说明） */
export function getBackupList() {
  return request.get('/backup')
}

/** 创建备份（data 含 type：全量/仅设备/仅告警/仅系统配置） */
export function createBackup(data) {
  return request.post('/backup', data)
}

/** 删除指定备份 */
export function deleteBackup(id) {
  return request.delete(`/backup/${id}`)
}

/** 导入备份内容（JSON 文本）并保存为备份记录 */
export function importBackup(data) {
  return request.post('/backup/import', data)
}

/** 按备份记录执行还原 */
export function restoreBackup(id) {
  return request.post(`/backup/${id}/restore`)
}
