/**
 * 系统配置 API：系统名称/时区、第三方 API（千问、DeepSeek 等）配置。
 */
import request from './request'

/** 系统配置项列表（如 system.name、system.timezone） */
export function getSystemConfig() {
  return request.get('/system/config')
}

/** 保存单条系统配置（configKey、configValue） */
export function saveSystemConfig(config) {
  return request.post('/system/config', config)
}

/** 第三方 API 配置（千问、DeepSeek 的 endpoint、key 等） */
export function getApiSettings() {
  return request.get('/system/api-settings')
}

/** 保存第三方 API 配置（供 AI 助手、网络 AI 命令等使用） */
export function saveApiSettings(data) {
  return request.post('/system/api-settings', data)
}

/** 检测第三方 API 连通性与鉴权状态 */
export function checkApiSettingsHealth() {
  return request.get('/system/api-settings/health-check', { timeout: 25000 })
}
