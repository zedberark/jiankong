/**
 * 前端 HTTP 客户端：统一 baseURL=/api、超时与请求头；
 * 请求头自动附带当前登录用户名（X-User-Name），后端用于审计与权限；
 * 401 时清除本地用户并跳转登录页，并带上当前路径以便登录后返回。
 */
import axios from 'axios'
import { STORAGE_KEYS } from '../utils/constants'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求前：从 localStorage 读取登录用户，将 username 写入 X-User-Name 请求头
request.interceptors.request.use((config) => {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.USER)
    if (raw != null && raw !== '') {
      const user = JSON.parse(raw)
      if (user && typeof user.username === 'string') config.headers['X-User-Name'] = user.username
      if (user && typeof user.token === 'string' && user.token.trim() !== '') {
        config.headers.Authorization = 'Bearer ' + user.token.trim()
      }
    }
  } catch (_) { /* 忽略解析异常，继续发送请求 */ }
  return config
})

// 响应：401 时清空本地用户并跳转登录，URL 带 from 参数便于登录后回到原页；网络错误时保留原错误
request.interceptors.response.use(
  (r) => r,
  (err) => {
    const status = err.response && err.response.status
    if (status === 401) {
      try { localStorage.removeItem(STORAGE_KEYS.USER) } catch (_) {}
      if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
        const from = (window.location.pathname || '') + (window.location.search || '')
        window.location.href = from ? '/login?from=' + encodeURIComponent(from) : '/login'
      }
    }
    return Promise.reject(err)
  }
)

export function isUnauthorizedError(err) {
  return err?.response?.status === 401
}

function isBackendUnavailableError(err) {
  if (!err) return false
  // 主动取消/中断不应提示“后端不可达”
  if (err.code === 'ERR_CANCELED' || err.name === 'CanceledError' || err.name === 'AbortError') return false
  // 超时单独提示，不归类后端不可达
  if (err.code === 'ECONNABORTED') return false
  // 非 axios 错误（如 html2canvas/jsPDF 抛出的 DOM/安全异常）没有 response，不能当成后端不可达
  if (!err.isAxiosError) return false
  // axios 网络层失败通常没有 response，如 ECONNREFUSED / ERR_NETWORK / CORS/network down
  if (!err.response) return true
  const status = Number(err.response.status)
  // 网关类错误通常代表后端不可达或异常
  return status === 502 || status === 503 || status === 504
}

export function getApiErrorHint(err, fallback = '请求失败，请稍后重试') {
  if (isUnauthorizedError(err)) return '登录已过期，请重新登录'
  if (err?.code === 'ERR_CANCELED' || err?.name === 'CanceledError' || err?.name === 'AbortError') return '请求已取消'
  if (err?.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试（巡检、批量命令等耗时较长；若设备很多可缩小分组或减少并发探测负载）'
  }
  if (isBackendUnavailableError(err)) return '后端服务不可达，请检查后端是否启动'
  return err?.response?.data?.message || err?.message || fallback
}

export default request
