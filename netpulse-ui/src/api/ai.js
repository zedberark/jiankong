/**
 * AI 运维助手 API：会话与消息；一次性对话（网络 AI 命令、批量命令优化）不建会话。
 */
import request from './request'

/** 当前用户的 AI 会话列表 */
export function getAiSessions() {
  return request.get('/ai/sessions')
}

/** 某会话的消息列表（多轮对话历史） */
export function getAiSessionMessages(sessionId) {
  return request.get(`/ai/sessions/${sessionId}/messages`)
}

/** 创建新会话（标题默认「新会话」） */
export function createAiSession() {
  return request.post('/ai/sessions')
}

/** 删除会话及其全部消息 */
export function deleteAiSession(sessionId) {
  return request.delete(`/ai/sessions/${sessionId}`)
}

/** options.transient === true 时仅一次性调用，不创建会话，不进入 AI 运维助手列表（用于网络 AI 命令、批量命令） */
export function aiChat(sessionId, message, options = {}) {
  const body = { sessionId, message }
  if (options.transient) body.transientChat = true
  return request.post('/ai/chat', body, { timeout: 90000 })
}
