/**
 * 批量命令 API：对多台 Linux 设备同时下发 SSH/Telnet 命令，返回每台执行结果。
 */
import request from './request'

/** 批量执行命令：单台 SSH 可能较慢，超时设为 2 分钟，避免全选多台时被误判为全部失败 */
const BATCH_COMMAND_TIMEOUT = 120000

/** 对多台设备执行同一条命令，返回各设备成功/失败及输出 */
export function executeBatchCommand(deviceIds, command) {
  return request.post('/batch/command', { deviceIds, command }, { timeout: BATCH_COMMAND_TIMEOUT })
}
