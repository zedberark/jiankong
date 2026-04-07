import { ref } from 'vue'
import { getSystemConfig } from '../api/system'

const DEFAULT_TZ = 'Asia/Shanghai'

/** 当前系统时区（用于全站时间显示），修改后需调用 loadSystemTimezone 从服务端拉取 */
export const systemTimezone = ref(DEFAULT_TZ)

export function getSystemTimezone() {
  return systemTimezone.value || DEFAULT_TZ
}

export function setSystemTimezone(tz) {
  systemTimezone.value = tz && String(tz).trim() ? tz.trim() : DEFAULT_TZ
}

/** 从系统配置接口拉取时区并更新（Layout 挂载时、系统设置保存后调用） */
export async function loadSystemTimezone() {
  try {
    const r = await getSystemConfig()
    const list = r.data || []
    const item = list.find(c => c.configKey === 'system.timezone')
    const tz = item && item.configValue && String(item.configValue).trim()
      ? item.configValue.trim()
      : DEFAULT_TZ
    systemTimezone.value = tz
    return tz
  } catch (e) {
    systemTimezone.value = DEFAULT_TZ
    return DEFAULT_TZ
  }
}

/**
 * 按系统配置的时区格式化时间，供全站统一使用。
 * @param {string|number|Date} t - 时间（ISO 字符串、时间戳或 Date）
 * @param {object} options - 可选，如 { dateStyle: 'short', timeStyle: 'short' } 或 { hour: '2-digit', minute: '2-digit' }
 */
export function formatTime(t, options = {}) {
  if (t == null || t === '') return '-'
  const date = typeof t === 'object' && t instanceof Date ? t : new Date(t)
  if (Number.isNaN(date.getTime())) return '-'
  const timeZone = getSystemTimezone()
  const base = {
    timeZone,
    hour12: false,
  }
  if (Object.keys(options).length) {
    return date.toLocaleString('zh-CN', { ...base, ...options })
  }
  return date.toLocaleString('zh-CN', base)
}
