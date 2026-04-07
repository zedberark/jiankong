<template>
  <div class="page ai-assistant-page">
    <div class="ai-layout">
      <aside class="session-sidebar">
        <div class="sidebar-header">
          <button class="btn-new" @click="startNewSession">+ 新会话</button>
          <button
            class="btn-bulk-delete"
            type="button"
            @click="bulkDelete"
            :disabled="!selectedSessionIds.length"
          >
            批量删除
          </button>
        </div>
        <div class="session-list">
          <div v-if="sessions.length" class="session-list-header">
            <label class="check-all">
              <input
                type="checkbox"
                :checked="isAllSessionsSelected"
                @change="selectedSessionIds = isAllSessionsSelected ? [] : sessions.map(s => s.id)"
              />
              全选会话
            </label>
          </div>
          <div
            v-for="s in sortedSessions"
            :key="s.id"
            class="session-item"
            :class="{ active: currentSessionId === s.id }"
          >
            <input
              type="checkbox"
              class="session-check"
              :value="s.id"
              v-model="selectedSessionIds"
              @click.stop
            />
            <div class="session-item-content" @click="selectSession(s.id)">
              <span class="session-title">{{ s.title || '新会话' }}</span>
              <span class="session-time">{{ formatTime(s.createTime) }}</span>
            </div>
            <div class="session-item-actions">
              <button type="button" class="btn-dots" title="更多" @click.stop="openMenuId = (openMenuId === s.id ? null : s.id)" aria-haspopup="true" :aria-expanded="openMenuId === s.id">⋮</button>
              <div v-if="openMenuId === s.id" class="session-menu" @click.stop>
                <button type="button" @click="togglePin(s.id)">{{ pinnedIds.includes(s.id) ? '取消置顶' : '置顶' }}</button>
                <button type="button" class="danger" @click="doDeleteSession(s)">删除会话</button>
              </div>
            </div>
          </div>
          <p v-if="!sessions.length && !loadingSessions" class="empty-hint">暂无会话，点击「新会话」开始</p>
        </div>
      </aside>
      <main class="chat-main">
        <div ref="messagesRef" class="messages">
          <div v-if="!currentSessionId && !messages.length" class="welcome">
            <p>我是 AI 运维助手，可结合当前虚拟机 Linux 与网络设备信息回答您的问题。</p>
          </div>
          <div v-for="m in displayMessages" :key="m.id" class="msg-row" :class="m.role">
            <div class="msg-bubble">
              <span v-if="m.role === 'assistant'" class="msg-role">助手</span>
              <div class="msg-content">{{ displayContent(m) }}</div>
              <div v-if="m.time || m.createTime" class="msg-time">{{ formatTime(m.time || m.createTime) }}</div>
            </div>
          </div>
          <div v-if="sending" class="msg-row assistant">
            <div class="msg-bubble"><span class="msg-role">助手</span><div class="msg-content">正在回复…</div></div>
          </div>
        </div>
        <div class="input-bar">
          <textarea v-model="inputText" placeholder="输入问题，如：当前有哪些设备离线？" rows="2" @keydown.enter.exact.prevent="send" />
          <button class="btn-send primary" @click="send" :disabled="sending || !inputText.trim()">发送</button>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { getAiSessions, getAiSessionMessages, createAiSession, deleteAiSession, aiChat } from '../api/ai'
import { getSystemTimezone } from '../utils/systemTimezone'

const PINNED_KEY = 'ai-assistant-pinned'
function getPinnedIds() {
  try {
    const raw = localStorage.getItem(PINNED_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}
function setPinnedIds(ids) {
  localStorage.setItem(PINNED_KEY, JSON.stringify(ids))
}

const sessions = ref([])
const messages = ref([])
const currentSessionId = ref(null)
const inputText = ref('')
const sending = ref(false)
const loadingSessions = ref(false)
const messagesRef = ref(null)
const openMenuId = ref(null)
const pinnedIds = ref(getPinnedIds())
const selectedSessionIds = ref([])

/** 仅展示用户与助手消息，不展示 system（上下文快照） */
const displayMessages = computed(() => {
  const list = messages.value || []
  return list.filter(m => m.role === 'user' || m.role === 'assistant')
})

/** 展示用正文：助手回复中去掉 ** 避免前端显示加粗符 */
function displayContent(m) {
  const text = m?.content ?? ''
  if (m?.role === 'assistant') return text.replace(/\*\*/g, '')
  return text
}

const isAllSessionsSelected = computed(() => {
  if (!sessions.value.length) return false
  return sessions.value.every(s => selectedSessionIds.value.includes(s.id))
})

const sortedSessions = computed(() => {
  const list = sessions.value || []
  const pinned = pinnedIds.value || []
  return [...list].sort((a, b) => {
    const aPin = pinned.includes(a.id)
    const bPin = pinned.includes(b.id)
    if (aPin && !bPin) return -1
    if (!aPin && bPin) return 1
    const at = a.createTime ? new Date(a.createTime).getTime() : 0
    const bt = b.createTime ? new Date(b.createTime).getTime() : 0
    return bt - at
  })
})

function togglePin(sessionId) {
  const ids = [...(pinnedIds.value || [])]
  const i = ids.indexOf(sessionId)
  if (i >= 0) ids.splice(i, 1)
  else ids.push(sessionId)
  pinnedIds.value = ids
  setPinnedIds(ids)
  openMenuId.value = null
}

function doDeleteSession(s) {
  openMenuId.value = null
  deleteAiSession(s.id)
    .then(() => {
      if (currentSessionId.value === s.id) {
        currentSessionId.value = null
        messages.value = []
      }
      loadSessions()
    })
    .catch(() => {})
}

function bulkDelete() {
  if (!selectedSessionIds.value.length) return
  if (!window.confirm(`确定要删除选中的 ${selectedSessionIds.value.length} 个会话吗？此操作不可恢复。`)) return
  const ids = [...selectedSessionIds.value]
  Promise.all(ids.map(id => deleteAiSession(id).catch(() => null)))
    .then(() => {
      if (currentSessionId.value && ids.includes(currentSessionId.value)) {
        currentSessionId.value = null
        messages.value = []
      }
      selectedSessionIds.value = []
      loadSessions()
    })
    .catch(() => {})
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const tz = getSystemTimezone()
  const opts = { timeZone: tz, hour: '2-digit', minute: '2-digit' }
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString('zh-CN', opts)
  return d.toLocaleDateString('zh-CN', { timeZone: tz, month: '2-digit', day: '2-digit' }) + ' ' + d.toLocaleTimeString('zh-CN', opts)
}

function loadSessions() {
  loadingSessions.value = true
  getAiSessions()
    .then(r => { sessions.value = r.data != null ? r.data : [] })
    .catch(() => { sessions.value = []; alert('会话列表加载失败，请检查网络或后端服务') })
    .finally(() => { loadingSessions.value = false })
}

function loadMessages() {
  if (!currentSessionId.value) {
    messages.value = []
    return
  }
  getAiSessionMessages(currentSessionId.value)
    .then(r => { messages.value = r.data || [] })
    .catch(() => { messages.value = [] })
    .finally(() => nextTick(scrollToBottom))
}

function scrollToBottom() {
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

function startNewSession() {
  createAiSession()
    .then(r => {
      const newSession = r.data || {}
      if (newSession.id) {
        currentSessionId.value = newSession.id
        messages.value = []
        inputText.value = ''
        loadSessions()
        nextTick(scrollToBottom)
      }
    })
    .catch(() => {})
}

function selectSession(id) {
  currentSessionId.value = id
  loadMessages()
}

function send() {
  const text = inputText.value.trim()
  if (!text || sending.value) return
  const sessionId = currentSessionId.value
  inputText.value = ''
  sending.value = true
  const nowIso = new Date().toISOString()
  messages.value.push({ id: Date.now(), role: 'user', content: text, time: nowIso })
  nextTick(scrollToBottom)
  aiChat(sessionId || undefined, text)
    .then(r => {
      const data = r.data || {}
      if (data.sessionId != null) currentSessionId.value = data.sessionId
      messages.value.push({ id: Date.now() + 1, role: 'assistant', content: data.reply ?? '', time: data.time || nowIso })
      loadSessions()
      nextTick(scrollToBottom)
    })
    .catch(e => {
      messages.value.push({
        id: Date.now() + 1,
        role: 'assistant',
        content: '请求失败：' + (e.response?.data?.message || e.message || '请检查 API 配置'),
        time: new Date().toISOString(),
      })
      nextTick(scrollToBottom)
    })
    .finally(() => { sending.value = false })
}

watch(currentSessionId, () => loadMessages())

function closeMenu() {
  openMenuId.value = null
}

onMounted(() => {
  loadSessions()
  document.addEventListener('click', closeMenu)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
})
</script>

<style scoped>
.ai-assistant-page { height: calc(100vh - 140px); min-height: 400px; padding: 0; }
.ai-layout { display: flex; height: 100%; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden; background: #fff; }
.session-sidebar {
  width: 230px; border-right: 1px solid #e5e7eb; display: flex; flex-direction: column; background: #fafbfc;
}
.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.35rem;
  padding: 0.75rem;
}
.btn-new {
  flex: 1;
  padding: 0.5rem 0.75rem; background: #2563eb; color: #fff; border: none; border-radius: 8px;
  font-size: 0.8125rem; cursor: pointer;
}
.btn-new:hover { background: #1d4ed8; }
.btn-bulk-delete {
  padding: 0.4rem 0.5rem;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 0.75rem;
  cursor: pointer;
}
.btn-bulk-delete:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.session-list { flex: 1; overflow-y: auto; padding: 0 0.5rem 0.5rem; }
.session-list-header {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0.35rem 0.5rem 0.25rem;
  font-size: 0.75rem;
  color: #6b7280;
}
.check-all input {
  margin-right: 0.25rem;
}
.session-item {
  display: flex; align-items: center; gap: 0.25rem;
  padding: 0.6rem 0.5rem; margin-bottom: 0.25rem; border-radius: 8px;
  border: 1px solid transparent;
}
.session-item:hover { background: #f3f4f6; }
.session-item.active { background: #eff6ff; border-color: #93c5fd; }
.session-check {
  margin-right: 0.25rem;
}
.session-item-content { flex: 1; min-width: 0; cursor: pointer; }
.session-item-actions { position: relative; flex-shrink: 0; }
.btn-dots {
  padding: 0.2rem 0.4rem; border: none; background: transparent; color: #6b7280;
  font-size: 1rem; line-height: 1; cursor: pointer; border-radius: 4px;
}
.btn-dots:hover { background: #e5e7eb; color: #111827; }
.session-menu {
  position: absolute; right: 0; top: 100%; margin-top: 2px; z-index: 10;
  min-width: 100px; padding: 0.25rem; background: #fff; border: 1px solid #e5e7eb;
  border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  display: flex; flex-direction: column; gap: 0;
}
.session-menu button {
  padding: 0.4rem 0.6rem; border: none; background: none; text-align: left;
  font-size: 0.8125rem; color: #374151; cursor: pointer; border-radius: 4px;
}
.session-menu button:hover { background: #f3f4f6; }
.session-menu button.danger { color: #dc2626; }
.session-menu button.danger:hover { background: #fef2f2; }
.session-title { display: block; font-size: 0.8125rem; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-time { font-size: 0.7rem; color: #9ca3af; }
.empty-hint { font-size: 0.75rem; color: #9ca3af; padding: 0.5rem; margin: 0; }
.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.messages { flex: 1; overflow-y: auto; padding: 1rem; }
.welcome { color: #6b7280; font-size: 0.9375rem; line-height: 1.6; padding: 1rem 0; }
.msg-row { margin-bottom: 1rem; }
.msg-row.user { display: flex; justify-content: flex-end; }
.msg-row.assistant { display: flex; justify-content: flex-start; }
.msg-bubble {
  max-width: 85%; padding: 0.6rem 0.9rem; border-radius: 12px; font-size: 0.9375rem;
}
.msg-row.user .msg-bubble { background: #2563eb; color: #fff; }
.msg-row.assistant .msg-bubble { background: #f3f4f6; color: #111827; }
.msg-role { font-size: 0.75rem; color: #6b7280; margin-bottom: 0.25rem; display: block; }
.msg-row.user .msg-role { color: rgba(255,255,255,0.8); }
.msg-content { white-space: pre-wrap; word-break: break-word; }
.msg-time {
  margin-top: 0.25rem;
  font-size: 0.75rem;
  opacity: 0.7;
  text-align: right;
}
.input-bar {
  border-top: 1px solid #e5e7eb; padding: 0.75rem 1rem; display: flex; gap: 0.5rem; align-items: flex-end;
}
.input-bar textarea {
  flex: 1; resize: none; padding: 0.5rem 0.75rem; border: 1px solid #d1d5db; border-radius: 8px; font-size: 0.9375rem;
}
.input-bar textarea:focus { outline: none; border-color: #2563eb; }
.btn-send { padding: 0.5rem 1rem; border-radius: 8px; border: none; cursor: pointer; font-size: 0.875rem; }
.btn-send:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
