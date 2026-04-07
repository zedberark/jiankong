<template>
  <div class="user-manage page">
    <header class="page-header">
      <div class="header-left">
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">管理用户、角色与可见菜单</p>
      </div>
      <div class="header-actions">
        <button type="button" class="primary small" @click="load" :disabled="listLoading">{{ listLoading ? '刷新中…' : '刷新' }}</button>
        <button class="btn-primary" @click="openForm()">
          <span class="btn-icon">+</span>
          添加用户
        </button>
      </div>
    </header>

    <div class="card template-card">
      <h3 class="template-title">角色权限模板</h3>
      <p class="template-desc">为每个角色配置可分配的菜单；用户登录后的侧栏 = 其所绑定各角色模板的菜单<strong>并集</strong>。此处保存<strong>只影响菜单权限</strong>，与下方「编辑用户」弹窗里的<strong>保存用户信息</strong>是两套操作。</p>
      <p class="template-warn">请先在下拉框中选择角色，勾选菜单后，必须点击本卡片内的「保存该角色模板」才会写入数据库。</p>
      <div class="template-form">
        <div class="form-group">
          <label>选择角色</label>
          <select v-model="templateRoleId" @change="loadRoleTemplate">
            <option value="">请选择角色</option>
            <option v-for="r in roles" :key="r.id" :value="String(r.id)">{{ r.name }}</option>
          </select>
        </div>
        <div v-if="templateRoleId" class="form-group">
          <label>该角色可分配菜单（可多选）</label>
          <div class="chips chips-scroll">
            <label v-for="m in allMenuOptions" :key="m.code" class="chip">
              <input type="checkbox" :value="m.code" v-model="templateMenuCodes" />
              {{ m.title }}
            </label>
          </div>
          <button type="button" class="btn-primary btn-small" @click="saveRoleTemplate" :disabled="templateSaving">
            {{ templateSaving ? '保存中…' : '保存该角色模板' }}
          </button>
        </div>
      </div>
    </div>

    <div class="table-card card table-loading-wrap">
      <div v-if="listLoading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <div class="table-scroll">
        <table class="user-table">
          <thead>
            <tr>
              <th class="col-id">ID</th>
              <th class="col-username">用户名</th>
              <th>角色</th>
              <th>邮箱</th>
              <th>告警通知</th>
              <th class="col-status">状态</th>
              <th class="col-ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td class="col-id"><code class="id-code">{{ u.id }}</code></td>
              <td class="col-username">
                <span class="username-text">{{ u.username }}</span>
                <span v-if="u.username === 'admin'" class="badge builtin">内置管理员</span>
              </td>
              <td class="col-roles">
                <span class="roles-text">{{ roleNamesFor(u.id) }}</span>
              </td>
              <td class="col-email">{{ u.email || '—' }}</td>
              <td>{{ notifyLevelsLabel(u.alertNotifyLevels) }}</td>
              <td class="col-status">
                <span :class="['status-badge', u.enabled ? 'status-enabled' : 'status-disabled']">
                  {{ u.enabled ? '启用' : '禁用' }}
                </span>
              </td>
              <td class="col-ops">
                <div class="ops-buttons">
                  <button type="button" class="op-btn op-edit" @click="openForm(u)">编辑</button>
                  <button v-if="u.username !== 'admin'" type="button" class="op-btn op-delete" @click="doDelete(u.id)">删除</button>
                  <span v-else class="no-del">不可删除</span>
                </div>
              </td>
            </tr>
            <tr v-if="!users.length">
              <td colspan="7" class="empty-cell">
                <div class="empty-state">
                  <span class="empty-icon">◇</span>
                  <p class="empty-text">暂无用户</p>
                  <p class="empty-hint">点击「添加用户」创建新账号</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal-dialog card">
        <div class="modal-header">
          <h3>{{ editingId ? '编辑用户' : '添加用户' }}</h3>
          <button type="button" class="modal-close" @click="showModal = false" aria-label="关闭">×</button>
        </div>

        <form @submit.prevent="submit" class="modal-form">
          <section class="form-section">
            <h4 class="form-section-title">账号信息</h4>
            <div class="form-row form-row-2">
              <div class="form-group">
                <label>用户名</label>
                <input v-model="form.username" required :readonly="!!editingId" placeholder="登录名" />
              </div>
              <div class="form-group">
                <label>邮箱（选填）</label>
                <input v-model="form.email" type="email" placeholder="用于告警通知，可不填" />
              </div>
            </div>
            <div class="form-group">
              <label>告警通知级别（在邮箱后配置）</label>
              <div class="chips">
                <label class="chip"><input type="checkbox" value="critical" v-model="form.alertNotifyLevels" /> 一级（严重）</label>
                <label class="chip"><input type="checkbox" value="warning" v-model="form.alertNotifyLevels" /> 二级（警告）</label>
                <label class="chip"><input type="checkbox" value="info" v-model="form.alertNotifyLevels" /> 三级（一般）</label>
              </div>
            </div>
            <div class="form-group">
              <label>密码</label>
              <input v-model="form.password" type="password" :placeholder="editingId ? '不填则保持原密码' : '必填'" />
            </div>
          </section>

          <section class="form-section">
            <h4 class="form-section-title">角色</h4>
            <p class="form-hint">可见菜单由「角色权限模板」决定。</p>
            <div class="chips">
              <label v-for="r in roles" :key="r.id" class="chip">
                <input type="checkbox" :value="r.id" v-model="form.roleIds" />
                {{ r.name }}
              </label>
            </div>
          </section>

          <section class="form-section">
            <h4 class="form-section-title">状态</h4>
            <label class="inline-check">
              <input type="checkbox" v-model="form.enabled" />
              启用
            </label>
          </section>

          <div class="form-actions">
            <button type="button" class="btn-secondary" @click="showModal = false">取消</button>
            <button type="submit" class="btn-primary">保存用户信息</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getUsers, getUserRoles, createUser, updateUser, deleteUser, getRoles, getRoleMenus, saveRoleMenus } from '../api/users'

const users = ref([])
const roles = ref([])
const userRoleIds = ref({})
const showModal = ref(false)
const editingId = ref(null)
const form = ref({
  username: '', password: '', email: '', enabled: true, roleIds: [], alertNotifyLevels: [],
})

/** 与后端 RoleMenuService 一致：旧库中 system-inspection / inspection-reports 合并为 inspection，否则复选框 value 对不上、保存后回显全空 */
const LEGACY_MENU_TO_CANONICAL = {
  'system-inspection': 'inspection',
  'inspection-reports': 'inspection',
}

const allMenuOptions = [
  { code: 'dashboard', title: '首页仪表盘' },
  { code: 'devices', title: '设备管理' },
  { code: 'metrics', title: '设备指标' },
  { code: 'topology', title: '设备拓扑图' },
  { code: 'alerts', title: '告警通知' },
  { code: 'inspection', title: '系统巡检（含手动巡检与报告）' },
  { code: 'batch-command', title: 'Linux 运维控制台' },
  { code: 'network-ai-command', title: '网络设备运维控制台' },
  { code: 'ai-assistant', title: 'AI 运维助手' },
  { code: 'audit', title: '操作日志' },
  { code: 'backup', title: '配置备份' },
  { code: 'users', title: '用户管理' },
  { code: 'system', title: '系统设置' },
]

const templateRoleId = ref('')
const templateMenuCodes = ref([])
const templateSaving = ref(false)
const listLoading = ref(false)

const allowedTemplateCodes = new Set(allMenuOptions.map((m) => m.code))

function normalizeMenuCodesFromApi(raw) {
  const arr = Array.isArray(raw) ? raw : []
  const out = new Set()
  for (const c of arr) {
    const s = String(c ?? '').trim()
    if (!s) continue
    const k = LEGACY_MENU_TO_CANONICAL[s] || s
    if (allowedTemplateCodes.has(k)) out.add(k)
  }
  return [...out]
}

async function loadRoleTemplate() {
  if (!templateRoleId.value) { templateMenuCodes.value = []; return }
  try {
    const r = await getRoleMenus(templateRoleId.value)
    templateMenuCodes.value = normalizeMenuCodesFromApi(r.data)
  } catch (e) {
    templateMenuCodes.value = []
    const msg = e?.response?.data?.message || e?.message || '加载角色模板失败'
    alert(typeof msg === 'string' ? msg : '加载角色模板失败，请检查网络或后端日志')
  }
}

function saveRoleTemplate() {
  const rid = templateRoleId.value
  if (!rid || String(rid).trim() === '') {
    alert('请先在「选择角色」下拉框中选择要配置的角色，再勾选菜单并保存。')
    return
  }
  templateSaving.value = true
  const codes = Array.isArray(templateMenuCodes.value) ? [...templateMenuCodes.value] : []
  saveRoleMenus(rid, codes)
    .then(() => {
      const n = codes.length
      alert(`角色权限模板已保存（共 ${n} 个菜单项）。使用该角色的用户需重新登录后侧栏才会更新。`)
      return loadRoleTemplate()
    })
    .catch((e) => {
      const msg = e.response?.data?.message || e.message || '保存失败'
      alert(typeof msg === 'string' ? msg : '保存失败，请检查网络或后端日志')
    })
    .finally(() => { templateSaving.value = false })
}

function roleNamesFor(userId) {
  const ids = userRoleIds.value[userId]
  if (!Array.isArray(ids) || !ids.length) return '-'
  const names = ids.map(id => roles.value.find(r => r.id === id)?.name).filter(Boolean)
  return names.length ? names.join('、') : '-'
}

function parseNotifyLevels(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  return String(raw).split(/[,\s]+/).map(s => s.trim().toLowerCase()).filter(Boolean)
}

function notifyLevelsLabel(raw) {
  const levels = parseNotifyLevels(raw)
  const map = { critical: '一级', warning: '二级', info: '三级' }
  const out = levels.map(v => map[v]).filter(Boolean)
  return out.length ? out.join('、') : '关闭'
}

function load() {
  listLoading.value = true
  Promise.all([getUsers(), getRoles()])
    .then(([usersRes, rolesRes]) => {
      users.value = usersRes.data != null ? usersRes.data : []
      roles.value = rolesRes.data != null ? rolesRes.data : []
      const list = users.value
      if (!list.length) { userRoleIds.value = {}; return }
      return Promise.all(list.map(u => getUserRoles(u.id))).then(roleIdLists => {
        const map = {}
        list.forEach((u, i) => { map[u.id] = roleIdLists[i]?.data || [] })
        userRoleIds.value = map
      })
    })
    .catch(() => {
      users.value = []
      roles.value = []
      userRoleIds.value = {}
      alert('用户列表加载失败，请检查网络或后端服务')
    })
    .finally(() => { listLoading.value = false })
}

function openForm(user = null) {
  editingId.value = user ? user.id : null
  form.value = user
    ? { ...user, password: '', roleIds: [], alertNotifyLevels: parseNotifyLevels(user.alertNotifyLevels) }
    : { username: '', password: '', email: '', enabled: true, roleIds: [], alertNotifyLevels: [] }
  if (user) {
    getUserRoles(user.id).then(r => { form.value.roleIds = r.data || [] })
  }
  showModal.value = true
}

async function submit() {
  if (!editingId.value && !form.value.password) { alert('请填写密码'); return }
  const payload = { ...form.value, alertNotifyLevels: (form.value.alertNotifyLevels || []).join(',') }
  try {
    editingId.value
      ? await updateUser(editingId.value, payload)
      : await createUser(payload)
    showModal.value = false
    load()
    alert(editingId.value ? '用户信息已保存（菜单权限仍由上方「角色权限模板」决定）' : '用户已创建')
  } catch (e) {
    alert(e.response?.data?.message || '保存失败')
  }
}

function doDelete(id) {
  const u = users.value.find(x => x.id === id)
  if (u && u.username === 'admin') return
  if (!confirm('确定删除该用户？')) return
  deleteUser(id).then(load).catch(e => alert(e.response?.data?.message || '删除失败'))
}

onMounted(load)
</script>

<style scoped>
.user-manage.page { display: flex; flex-direction: column; gap: 1.25rem; }

/* ---------- 页面头部 ---------- */
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
.header-left { flex: 1; min-width: 0; }
.header-actions { display: flex; align-items: center; gap: 0.5rem; flex-shrink: 0; }
.page-title { margin: 0 0 0.25rem; font-size: 1.375rem; font-weight: 600; color: #0f172a; letter-spacing: -0.02em; }
.page-desc { margin: 0; font-size: 0.875rem; color: #64748b; }
.btn-primary {
  display: inline-flex; align-items: center; gap: 0.5rem;
  padding: 0.6rem 1.1rem; font-size: 0.875rem; font-weight: 500;
  background: linear-gradient(180deg, #0ea5e9 0%, #0284c7 100%);
  border: none; border-radius: 10px; color: #fff;
  cursor: pointer; box-shadow: 0 1px 3px rgba(14, 165, 233, 0.3);
  transition: box-shadow 0.2s, transform 0.15s;
}
.btn-primary:hover { box-shadow: 0 4px 12px rgba(14, 165, 233, 0.35); transform: translateY(-1px); }
.btn-primary .btn-icon { font-size: 1.1rem; line-height: 1; opacity: 0.95; }

/* ---------- 工具栏 ---------- */
.toolbar.card {
  display: flex; align-items: center; justify-content: space-between;
  gap: 1rem; flex-wrap: wrap;
  padding: 0.75rem 1.25rem; border-radius: 12px;
  background: #fff; border: 1px solid #e2e8f0;
}
.toolbar-group { display: flex; align-items: center; gap: 0.5rem; }
.btn-tool {
  padding: 0.45rem 0.9rem; font-size: 0.8125rem; font-weight: 500;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; color: #475569;
  cursor: pointer; transition: background 0.2s, border-color 0.2s;
}
.btn-tool:hover { background: #f1f5f9; border-color: #cbd5e1; color: #334155; }
.toolbar-tip { font-size: 0.8125rem; color: #64748b; }

/* ---------- 角色权限模板 ---------- */
.template-card { padding: 1rem 1.25rem; border-radius: 12px; border: 1px solid #e2e8f0; background: #fff; }
.template-title { margin: 0 0 0.35rem; font-size: 1rem; font-weight: 600; color: #0f172a; }
.template-desc { margin: 0 0 0.5rem; font-size: 0.8125rem; color: #64748b; line-height: 1.5; }
.template-warn { margin: 0 0 1rem; padding: 0.6rem 0.75rem; font-size: 0.8125rem; color: #92400e; background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; line-height: 1.45; }
.template-form .form-group { margin-bottom: 1rem; }
.template-form .form-group label { display: block; margin-bottom: 0.35rem; font-size: 0.875rem; color: #374151; }
.template-form select { padding: 0.5rem 0.75rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; min-width: 160px; }
.template-form .btn-small { margin-top: 0.5rem; padding: 0.45rem 1rem; font-size: 0.8125rem; }
.hint-box { padding: 0.75rem 1rem; background: #fef3c7; border: 1px solid #fde68a; border-radius: 8px; font-size: 0.8125rem; color: #92400e; margin-bottom: 0.5rem; }

/* ---------- 表格 ---------- */
.table-card.card {
  border-radius: 12px; overflow: hidden;
  border: 1px solid #e2e8f0; background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.table-scroll { overflow-x: auto; }
.user-table { border-collapse: collapse; width: 100%; min-width: 860px; }
.user-table th, .user-table td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.user-table th { background: #f8fafc; font-weight: 600; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; color: #64748b; }
.user-table tbody tr { transition: background 0.12s; }
.user-table tbody tr:hover td { background: #fafbfc; }
.col-id { width: 90px; }
.id-code { padding: 0.15rem 0.45rem; background: #f1f5f9; border-radius: 6px; font-family: ui-monospace, monospace; color: #475569; }
.username-text { font-weight: 600; color: #0f172a; margin-right: 0.5rem; }
.roles-text { color: #475569; font-size: 0.875rem; }
.col-email { color: #64748b; font-size: 0.875rem; }
.status-badge { display: inline-flex; align-items: center; padding: 0.2rem 0.6rem; border-radius: 9999px; font-size: 0.75rem; font-weight: 500; }
.status-enabled { background: #d1fae5; color: #047857; }
.status-disabled { background: #f1f5f9; color: #64748b; }
.col-ops { white-space: nowrap; }
.ops-buttons { display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
.op-btn { padding: 0.35rem 0.7rem; font-size: 0.75rem; font-weight: 500; border-radius: 6px; border: 1px solid; cursor: pointer; }
.op-edit { background: #f0f9ff; border-color: #bae6fd; color: #0284c7; }
.op-edit:hover { background: #e0f2fe; }
.op-delete { background: #fef2f2; border-color: #fecaca; color: #dc2626; }
.op-delete:hover { background: #fee2e2; }
.no-del { font-size: 0.8125rem; color: #94a3b8; }
.badge.builtin { background: #dbeafe; color: #1d4ed8; padding: 0.2rem 0.5rem; border-radius: 6px; font-size: 0.75rem; }

/* 空状态 */
.empty-cell { padding: 0 !important; }
.empty-state { padding: 3rem 1.5rem; text-align: center; display: flex; flex-direction: column; align-items: center; }
.empty-icon { font-size: 2rem; color: #cbd5e1; margin-bottom: 0.75rem; }
.empty-text { margin: 0 0 0.25rem; font-size: 0.9375rem; font-weight: 500; color: #64748b; }
.empty-hint { margin: 0; font-size: 0.8125rem; color: #94a3b8; }

/* ---------- 弹窗 ---------- */
.modal-overlay {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(15, 23, 42, 0.45); backdrop-filter: blur(6px);
  display: flex; align-items: center; justify-content: center; padding: 1rem;
}
.modal-dialog {
  width: 100%; max-width: 560px; max-height: 90vh;
  overflow: hidden; display: flex; flex-direction: column;
  border-radius: 16px; box-shadow: 0 24px 48px rgba(0, 0, 0, 0.12);
}
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 1.25rem 1.5rem; border-bottom: 1px solid #f1f5f9; }
.modal-header h3 { margin: 0; font-size: 1.125rem; font-weight: 600; color: #0f172a; }
.modal-close {
  width: 32px; height: 32px; padding: 0; font-size: 1.25rem; line-height: 1;
  background: transparent; border: none; border-radius: 8px; color: #64748b; cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.modal-close:hover { background: #f1f5f9; color: #334155; }
.modal-form { overflow-y: auto; padding: 0 1.5rem 1.5rem; }
.form-section { margin-top: 1.25rem; }
.form-section-title {
  margin: 0 0 0.75rem; font-size: 0.8125rem; font-weight: 600; color: #64748b;
  text-transform: uppercase; letter-spacing: 0.04em;
}
.form-hint { margin: 0 0 0.5rem; font-size: 0.8125rem; color: #64748b; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 1rem; margin-bottom: 0.5rem; }
.section-tip { font-size: 0.75rem; color: #94a3b8; }
.form-row { display: flex; gap: 1rem; }
.form-row-2 > .form-group { flex: 1; min-width: 0; }
.form-group { margin-bottom: 1rem; }
.form-group label { display: block; margin-bottom: 0.35rem; font-size: 0.8125rem; font-weight: 500; color: #475569; }
.form-group input, .form-group textarea {
  width: 100%; padding: 0.5rem 0.75rem; font-size: 0.875rem;
  border: 1px solid #e2e8f0; border-radius: 8px; background: #fff;
}
.form-group input:focus, .form-group textarea:focus {
  outline: none; border-color: #0ea5e9; box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.12);
}
.chips { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.chips-scroll { max-height: 12rem; overflow-y: auto; padding: 0.25rem; border: 1px solid #f1f5f9; border-radius: 10px; background: #fbfdff; }
.chip { display: inline-flex; align-items: center; gap: 0.35rem; font-size: 0.875rem; cursor: pointer; color: #334155; }
.inline-check { display: inline-flex; align-items: center; gap: 0.5rem; font-size: 0.875rem; color: #334155; }
.form-actions {
  display: flex; justify-content: flex-end; gap: 0.75rem;
  margin-top: 1.5rem; padding-top: 1.25rem; border-top: 1px solid #f1f5f9;
}
.btn-secondary {
  padding: 0.5rem 1rem; font-size: 0.875rem; font-weight: 500;
  background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 8px; color: #475569;
  cursor: pointer; transition: background 0.2s, border-color 0.2s;
}
.btn-secondary:hover { background: #e2e8f0; border-color: #cbd5e1; }
</style>
