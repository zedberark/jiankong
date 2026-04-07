<template>
  <div class="device-list page">
    <header class="page-header">
      <div class="header-left">
        <h2 class="page-title">设备列表</h2>
        <button type="button" class="primary small" @click="load" :disabled="loading">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
        <label class="header-filter">
          <span class="filter-label">分组</span>
          <select v-model="filterGroup" @change="debouncedLoad" class="filter-select">
            <option value="">全部</option>
            <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
          </select>
        </label>
      </div>
      <div class="header-actions">
        <button type="button" class="btn-secondary" @click="openImportModal">
          批量添加
        </button>
        <button class="btn-primary" @click="openForm()">
          <span class="btn-icon">+</span>
          添加设备
        </button>
      </div>
    </header>

    <div class="table-card card table-loading-wrap">
      <div v-if="loading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <div class="table-scroll">
        <table class="device-table">
          <thead>
            <tr>
              <th class="col-check">
                <input type="checkbox" :checked="selectedAll" :indeterminate="selectedSome" @change="toggleSelectAll" aria-label="全选" />
              </th>
              <th class="th-name">设备名称</th>
              <th class="th-sortable" @click="toggleSort('ip')">
                IP 地址 <span class="sort-arrow">{{ sortArrow('ip') }}</span>
              </th>
              <th class="th-sortable" @click="toggleSort('status')">
                状态 <span class="sort-arrow">{{ sortArrow('status') }}</span>
              </th>
              <th class="th-vendor">厂商</th>
              <th class="th-sortable" @click="toggleSort('type')">
                类型 <span class="sort-arrow">{{ sortArrow('type') }}</span>
              </th>
              <th>分组</th>
              <th class="th-remark">备注</th>
              <th class="col-ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in paginatedDevices" :key="d.id" :class="{ selected: selectedIds.has(d.id) }">
              <td class="col-check">
                <input type="checkbox" :checked="selectedIds.has(d.id)" @change="toggleSelect(d.id)" :aria-label="'选择 ' + d.name" />
              </td>
              <td class="col-name">
                <span class="name-text">{{ d.name }}</span>
              </td>
              <td class="col-ip">
                <code class="ip-code">{{ d.ip }}</code>
              </td>
              <td class="col-status">
                <span :class="['status-badge', 'status-' + (d.status || 'normal')]">{{ deviceStatusText(d.status) }}</span>
              </td>
              <td class="col-vendor">
                <span class="tag tag-vendor">{{ d.vendor || '—' }}</span>
              </td>
              <td class="col-type">
                <span class="tag tag-type">{{ typeLabel(d.type) }}</span>
              </td>
              <td class="col-group">{{ d.groupName || '默认分组' }}</td>
              <td class="col-remark" :title="d.remark || ''">{{ d.remark || '—' }}</td>
              <td class="col-ops">
                <div class="ops-buttons">
                  <button v-if="isLinuxWithoutName(d) && d.status !== 'offline'" type="button" class="op-btn op-fetch-hostname" @click="doFetchHostname(d.id)" :disabled="fetchHostnameId === d.id" :title="fetchHostnameId === d.id ? '获取中…' : '通过 SSH 获取主机名并填入设备名称'">{{ fetchHostnameId === d.id ? '获取中…' : '获取主机名' }}</button>
                  <button type="button" class="op-btn op-edit" @click="openForm(d)" title="编辑">编辑</button>
                  <button type="button" class="op-btn op-ping" @click="doPing(d.id)" :disabled="!!pingId" :title="pingId === d.id ? '检测中…' : 'Ping 检测'">{{ pingId === d.id ? 'Ping中…' : 'Ping' }}</button>
                  <button type="button" class="op-btn op-delete" @click="doDelete(d.id)" title="删除">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!sortedDevices.length">
              <td colspan="9" class="empty-cell">
                <div class="empty-state">
                  <span class="empty-icon">◇</span>
                  <p class="empty-text">暂无设备</p>
                  <p class="empty-hint">点击「添加设备」开始添加</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="sortedDevices.length > 0" class="pagination-bar">
        <span class="pagination-info">共 {{ sortedDevices.length }} 条，每页 {{ PAGE_SIZE }} 条，第 {{ currentPage }}/{{ totalPages }} 页</span>
        <div class="pagination-btns">
          <button type="button" class="op-btn" :disabled="currentPage <= 1" @click="currentPage = Math.max(1, currentPage - 1)">上一页</button>
          <button type="button" class="op-btn" :disabled="currentPage >= totalPages" @click="currentPage = Math.min(totalPages, currentPage + 1)">下一页</button>
        </div>
      </div>
      <div v-if="selectedIds.size > 0" class="batch-bar">
        <span class="batch-count">已选 {{ selectedIds.size }} 台</span>
        <button type="button" class="btn-danger-sm" @click="batchDelete">批量删除</button>
      </div>
    </div>

    <!-- 居中提示弹窗（替代原生 alert） -->
    <Teleport to="body">
      <Transition name="toast-fade">
        <div v-if="toastVisible" class="toast-overlay" @click.self="toastVisible = false">
          <div class="toast-card" :class="'toast-' + toastType">
            <div class="toast-title">
              <span class="toast-dot"></span>
              <span>{{ toastType === 'success' ? '成功' : toastType === 'warning' ? '提示' : toastType === 'error' ? '错误' : '提示' }}</span>
            </div>
            <p class="toast-message">{{ toastMessage }}</p>
            <button type="button" class="toast-btn" @click="toastVisible = false">确定</button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal-dialog card">
        <div class="modal-header">
          <h3>{{ editingId ? '编辑设备' : '添加设备' }}</h3>
          <button type="button" class="modal-close" @click="showModal = false" aria-label="关闭">×</button>
        </div>
        <p v-if="!canSeePassword" class="admin-hint admin-lock">仅<strong>管理员</strong>可查看密码；所有人可修改密码（下方输入新密码即修改，留空则不修改）。</p>
        <form @submit.prevent="submit" class="modal-form">
          <section class="form-section">
            <h4 class="form-section-title">基本信息</h4>
            <div class="form-row form-row-2">
              <div class="form-group">
                <label>名称</label>
                <input v-model="form.name" placeholder="设备名称（选填；类型为服务器且已配 SSH 时，保存后在线则自动从主机名填充）" />
              </div>
              <div class="form-group">
                <label>类型</label>
                <select v-model="form.type" required>
                  <option value="server">服务器 / Linux</option>
                  <option value="firewall">防火墙</option>
                  <option value="switch">交换机</option>
                  <option value="router">路由器</option>
                </select>
              </div>
            </div>
            <div class="form-group">
              <label>管理 IP</label>
              <input v-model="form.ip" required placeholder="192.168.1.1" :class="{ 'input-invalid': ipError }" @input="ipError = ''" />
              <p v-if="ipError" class="form-error">{{ ipError }}</p>
              <p v-else class="form-hint-inline">格式：四段数字，如 192.168.1.1（每段 0～255）</p>
            </div>
            <div class="form-row form-row-2">
              <div class="form-group">
                <label>分组 / 标签</label>
                <input v-model="form.groupName" placeholder="如：生产区、机房A，可选" list="group-datalist" />
                <datalist id="group-datalist">
                  <option v-for="g in groupList" :key="g" :value="g" />
                </datalist>
              </div>
              <div class="form-group">
                <label>厂商</label>
                <input v-model="form.vendor" placeholder="厂商，可选" />
              </div>
            </div>
            <div class="form-group">
              <label>备注</label>
              <input v-model="form.remark" placeholder="备注信息，可选" />
            </div>
          </section>
          <section class="form-section">
            <h4 class="form-section-title">SSH / Telnet</h4>
            <div class="form-row form-row-2">
              <div class="form-group form-group-inline-hint">
                <label>端口</label>
                <div class="input-with-hint">
                  <input v-model.number="form.sshPort" type="number" placeholder="22" title="22=SSH，23=Telnet" />
                  <span class="form-hint-inline">22=SSH，23=Telnet（同一 Web 终端）</span>
                </div>
              </div>
              <div class="form-group">
                <label>用户名</label>
                <input v-model="form.sshUser" placeholder="root" />
              </div>
            </div>
            <div class="form-group" v-if="canSeePassword">
<label>SSH/Telnet 密码</label>
                <div class="password-wrap">
                <input v-model="form.sshPassword" :type="showSshPwd ? 'text' : 'password'" placeholder="用于 Web SSH / Telnet" />
                <button type="button" class="pw-toggle" :class="{ visible: showSshPwd }" :title="showSshPwd ? '隐藏密码' : '显示密码'" @click="showSshPwd = !showSshPwd" aria-label="显示/隐藏密码">
                  <svg v-if="showSshPwd" class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  <svg v-else class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
              </div>
            </div>
            <div class="form-group" v-else-if="form.sshUser">
              <label>SSH/Telnet 新密码</label>
              <input v-model="form.sshPassword" type="password" placeholder="输入新密码以修改，留空则不修改" />
            </div>
          </section>
          <template v-if="form.type !== 'server'">
            <section class="form-section">
              <h4 class="form-section-title">SNMP</h4>
              <div class="form-group">
                <label>版本</label>
                <select v-model="form.snmpVersion">
                  <option value="v2c">v2c（社区名）</option>
                  <option value="v3">v3（用户名 + 认证/加密）</option>
                </select>
              </div>
              <div v-if="form.snmpVersion === 'v2c'" class="form-group">
                <label>社区名</label>
                <input v-model="form.snmpCommunity" placeholder="public" />
              </div>
              <template v-if="form.snmpVersion === 'v3'">
                <div class="form-group">
                  <label>用户名</label>
                  <input v-model="form.snmpUsername" placeholder="v3 用户名" />
                </div>
                <template v-if="canSeePassword">
                  <div class="form-group">
                    <label>认证密码</label>
                    <div class="password-wrap">
                      <input v-model="form.snmpAuthPassword" :type="showSnmpAuthPwd ? 'text' : 'password'" placeholder="SNMPv3 认证密码" />
                      <button type="button" class="pw-toggle" :class="{ visible: showSnmpAuthPwd }" :title="showSnmpAuthPwd ? '隐藏密码' : '显示密码'" @click="showSnmpAuthPwd = !showSnmpAuthPwd" aria-label="显示/隐藏密码">
                        <svg v-if="showSnmpAuthPwd" class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                        <svg v-else class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                      </button>
                    </div>
                  </div>
                  <div class="form-group">
                    <label>加密密码</label>
                    <div class="password-wrap">
                      <input v-model="form.snmpPrivPassword" :type="showSnmpPrivPwd ? 'text' : 'password'" placeholder="SNMPv3 加密密码" />
                      <button type="button" class="pw-toggle" :class="{ visible: showSnmpPrivPwd }" :title="showSnmpPrivPwd ? '隐藏密码' : '显示密码'" @click="showSnmpPrivPwd = !showSnmpPrivPwd" aria-label="显示/隐藏密码">
                        <svg v-if="showSnmpPrivPwd" class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                        <svg v-else class="pw-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                      </button>
                    </div>
                  </div>
                </template>
                <template v-else-if="form.snmpUsername">
                  <div class="form-group">
                    <label>新认证密码</label>
                    <input v-model="form.snmpAuthPassword" type="password" placeholder="留空则不修改" />
                  </div>
                  <div class="form-group">
                    <label>新加密密码</label>
                    <input v-model="form.snmpPrivPassword" type="password" placeholder="留空则不修改" />
                  </div>
                </template>
              </template>
            </section>
          </template>
          <div class="form-actions">
            <button type="button" class="btn-secondary" @click="showModal = false">取消</button>
            <button type="submit" class="btn-primary">保存</button>
          </div>
        </form>
      </div>
    </div>

    <!-- 批量添加弹窗（Excel 表格式：IP、用户、密码、类型） -->
    <div v-if="showImportModal" class="modal-overlay" @click.self="showImportModal = false">
      <div class="modal-dialog card modal-import modal-import-table">
        <div class="modal-header">
          <h3>批量添加设备</h3>
          <button type="button" class="modal-close" @click="showImportModal = false" aria-label="关闭">×</button>
        </div>
        <p class="import-hint">填写下表，每行一台设备；IP、用户、密码、类型均为必填。</p>
        <div class="import-body">
          <div class="import-table-wrap">
            <table class="import-table">
              <thead>
                <tr>
                  <th>IP</th>
                  <th>用户</th>
                  <th>密码</th>
                  <th>类型</th>
                  <th class="col-import-op"></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in importRows" :key="idx">
                  <td><input v-model.trim="row.ip" type="text" placeholder="192.168.1.1" class="import-cell" /></td>
                  <td><input v-model.trim="row.sshUser" type="text" placeholder="root" class="import-cell" /></td>
                  <td><input v-model.trim="row.sshPassword" type="password" placeholder="密码" class="import-cell" /></td>
                  <td>
                    <select v-model="row.type" class="import-cell import-select">
                      <option value="server">服务器</option>
                      <option value="router">路由器</option>
                      <option value="switch">交换机</option>
                      <option value="firewall">防火墙</option>
                      <option value="other">其他</option>
                    </select>
                  </td>
                  <td class="col-import-op">
                    <button v-if="importRows.length > 1" type="button" class="op-btn op-delete-sm" @click="removeImportRow(idx)" title="删除本行">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <button type="button" class="btn-add-row" @click="addImportRow">+ 添加一行</button>
          <div class="form-actions">
            <button type="button" class="btn-secondary" @click="showImportModal = false">取消</button>
            <button type="button" class="btn-primary" @click="submitImport" :disabled="importLoading">{{ importLoading ? '添加中…' : '确定添加' }}</button>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
/**
 * 设备管理页：设备列表（分组筛选、排序、多选）、添加/编辑/删除、Ping。
 * 仅 ADMIN 可查看/编辑 SSH 与 SNMP 密码；表单提交时 SNMP v3 密码按需写回 snmpSecurity JSON。
 */
import { ref, computed, watch, onMounted } from 'vue'
import { getDevices, createDevice, updateDevice, deleteDevice, pingDevice, getDeviceGroups, fetchDeviceHostname } from '../api/device'
import { typeLabel, deviceStatusText } from '../utils/deviceLabels'
import { STORAGE_KEYS } from '../utils/constants'
import { debounce } from '../utils/debounce'

// ---------- 权限：仅 ADMIN 可查看/编辑设备密码（SSH、SNMP） ----------
const userRoles = ref([])
function hasRole(code) {
  const roles = userRoles.value
  if (!Array.isArray(roles) || roles.length === 0) return false
  return roles.includes(code)
}
const canSeePassword = computed(() => hasRole('ADMIN'))

// ---------- 列表数据与排序 ----------
const devices = ref([])
const sortBy = ref('status') // 默认按状态排序，在线设备在前
const sortOrder = ref('asc') // 'asc' = 在线在前，'desc' = 离线在前

/** 每页条数 */
const PAGE_SIZE = 10
/** 当前页码（从 1 开始） */
const currentPage = ref(1)

/** 设备类型排序顺序：服务器 → 防火墙 → 路由器 → 交换机 → 其他 */
const TYPE_SORT_ORDER = { server: 0, firewall: 1, router: 2, switch: 3, other: 4 }
function typeSortIndex(d) {
  const t = d?.type
  const typeStr = typeof t === 'string' ? t : (t?.toValue?.() ?? 'other')
  return TYPE_SORT_ORDER[typeStr] ?? 5
}

/** 状态排序权重：在线（normal/warning/critical）在前，离线在后 */
function statusSortIndex(d) {
  const s = (d && d.status) ? String(d.status) : 'offline'
  const map = { normal: 0, warning: 1, critical: 2, offline: 3 }
  return map[s] ?? 3
}

/** 当前排序后的设备列表，支持按 ip/type/status 升序或降序；默认按状态，在线设备在前 */
const sortedDevices = computed(() => {
  const list = [...devices.value]
  if (!sortBy.value) return list
  const key = sortBy.value
  const order = sortOrder.value === 'asc' ? 1 : -1
  list.sort((a, b) => {
    if (key === 'type') {
      const ia = typeSortIndex(a)
      const ib = typeSortIndex(b)
      return order * (ia - ib)
    }
    if (key === 'status') {
      const ia = statusSortIndex(a)
      const ib = statusSortIndex(b)
      return order * (ia - ib)
    }
    let va = a[key] ?? ''
    let vb = b[key] ?? ''
    if (key === 'ip') {
      va = (va + '').trim()
      vb = (vb + '').trim()
      return order * (va.localeCompare(vb, undefined, { numeric: true }))
    }
    va = String(va)
    vb = String(vb)
    return order * va.localeCompare(vb)
  })
  return list
})

/** 总页数 */
const totalPages = computed(() => Math.max(1, Math.ceil(sortedDevices.value.length / PAGE_SIZE)))
/** 当前页要展示的设备（10 条一页） */
const paginatedDevices = computed(() => {
  const list = sortedDevices.value
  const totalP = totalPages.value
  const page = Math.min(currentPage.value, totalP)
  const start = (page - 1) * PAGE_SIZE
  return list.slice(start, start + PAGE_SIZE)
})
watch(totalPages, (totalP) => {
  if (currentPage.value > totalP) currentPage.value = totalP
})

/** 点击表头切换排序字段或升降序 */
function toggleSort(field) {
  if (sortBy.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortBy.value = field
    sortOrder.value = 'asc'
  }
}

function sortArrow(field) {
  if (sortBy.value !== field) return '↕'
  return sortOrder.value === 'asc' ? '↑' : '↓'
}

// ---------- 分组、加载状态、弹窗与表单 ----------
const groupList = ref([])
const filterGroup = ref('')
const loading = ref(false)
const fetchHostnameId = ref(null)
const pingId = ref(null)
const showModal = ref(false)
const toastVisible = ref(false)
const toastMessage = ref('')
const toastType = ref('info')
function showToast(msg, type = 'info') {
  toastMessage.value = msg || ''
  toastType.value = type
  toastVisible.value = true
}
const editingId = ref(null)
const editingDevice = ref(null)
const showSshPwd = ref(false)
const showSnmpAuthPwd = ref(false)
const showSnmpPrivPwd = ref(false)
const ipError = ref('')
const form = ref({
  name: '',
  type: 'server',
  ip: '',
  sshPort: 22,
  sshUser: '',
  sshPassword: '',
  snmpCommunity: '',
  snmpVersion: 'v2c',
  snmpUsername: '',
  snmpAuthPassword: '',
  snmpPrivPassword: '',
  vendor: '',
  model: '',
  remark: '',
})

// ---------- 批量添加（Excel 表格式：IP、用户、密码、类型） ----------
const showImportModal = ref(false)
const importRows = ref([])
const importLoading = ref(false)
const ipRegex = /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/

function openImportModal() {
  importRows.value = [
    { ip: '', sshUser: '', sshPassword: '', type: 'server' },
    { ip: '', sshUser: '', sshPassword: '', type: 'server' },
    { ip: '', sshUser: '', sshPassword: '', type: 'server' },
    { ip: '', sshUser: '', sshPassword: '', type: 'server' },
    { ip: '', sshUser: '', sshPassword: '', type: 'server' },
  ]
  showImportModal.value = true
}

function addImportRow() {
  importRows.value.push({ ip: '', sshUser: '', sshPassword: '', type: 'server' })
}

function removeImportRow(idx) {
  if (importRows.value.length <= 1) return
  importRows.value.splice(idx, 1)
}

/** 批量添加：只提交填满 IP、用户、密码、类型的行，逐条 createDevice */
function submitImport() {
  const rows = importRows.value.filter(r => (r.ip || '').trim() && (r.sshUser || '').trim() && (r.sshPassword || '').trim())
  if (rows.length === 0) {
    showToast('请至少填写一行完整的 IP、用户、密码、类型', 'warning')
    return
  }
  const valid = rows.filter(r => ipRegex.test(String(r.ip).trim()))
  if (valid.length === 0) {
    showToast('请填写合法的 IPv4 地址', 'warning')
    return
  }
  if (valid.length < rows.length) {
    showToast('部分行 IP 格式不正确，将只添加合法 IP 的行', 'warning')
  }
  importLoading.value = true
  let done = 0
  let failed = 0
  function run(index) {
    if (index >= valid.length) {
      importLoading.value = false
      showImportModal.value = false
      load()
      showToast(`已添加 ${done} 台设备${failed ? '，失败 ' + failed + ' 台' : ''}`, failed ? 'warning' : 'success')
      return
    }
    const row = valid[index]
    const payload = {
      ip: String(row.ip).trim(),
      type: row.type || 'server',
      sshUser: String(row.sshUser).trim(),
      sshPassword: String(row.sshPassword).trim(),
      name: '',
      groupName: '',
      remark: '',
      sshPort: 22,
    }
    createDevice(payload).then(() => { done++; run(index + 1) }).catch(() => { failed++; run(index + 1) })
  }
  run(0)
}

// ---------- 多选与批量操作 ----------
const selectedIds = ref(new Set())

/** 当前页是否全选 */
const selectedAll = computed(() => {
  const page = paginatedDevices.value
  return page.length > 0 && page.every(d => selectedIds.value.has(d.id))
})
/** 当前页是否部分选中 */
const selectedSome = computed(() => {
  const page = paginatedDevices.value
  const n = page.filter(d => selectedIds.value.has(d.id)).length
  return n > 0 && n < page.length
})

function toggleSelect(id) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  const page = paginatedDevices.value
  const pageIds = new Set(page.map(d => d.id))
  const allSelected = page.length > 0 && page.every(d => selectedIds.value.has(d.id))
  const next = new Set(selectedIds.value)
  if (allSelected) {
    pageIds.forEach(id => next.delete(id))
  } else {
    pageIds.forEach(id => next.add(id))
  }
  selectedIds.value = next
}

/** 批量删除已选设备，确认后并发请求并刷新列表 */
function batchDelete() {
  if (!selectedIds.value.size || !confirm(`确定删除已选 ${selectedIds.value.size} 台设备？`)) return
  Promise.all([...selectedIds.value].map(id => deleteDevice(id)))
    .then(() => { selectedIds.value = new Set(); load() })
    .catch(e => showToast(e.response?.data?.message || '删除失败', 'error'))
}

/** 拉取设备列表与分组列表，按当前筛选分组 */
function load() {
  loading.value = true
  getDeviceGroups().then(r => { groupList.value = r?.data ?? [] }).catch(() => { groupList.value = [] })
  return getDevices(filterGroup.value ? { group: filterGroup.value } : {})
    .then(r => { devices.value = r.data != null ? r.data : []; currentPage.value = 1 })
    .catch(() => { devices.value = []; showToast('设备列表加载失败，请检查网络或后端服务', 'error') })
    .finally(() => { loading.value = false })
}
/** 筛选变更时防抖拉取，减少重复请求 */
const debouncedLoad = debounce(load, 300)

/** 打开添加/编辑弹窗：编辑时回填表单，SNMP v3 从 snmpSecurity JSON 解析出用户名与密码（管理员可见） */
function openForm(device = null) {
  showSshPwd.value = false
  showSnmpAuthPwd.value = false
  showSnmpPrivPwd.value = false
  ipError.value = ''
  editingId.value = device ? device.id : null
  editingDevice.value = device ? { ...device } : null
  if (device) {
    const base = { ...device }
    if (device.snmpVersion === 'v3' && device.snmpSecurity) {
      try {
        const sec = typeof device.snmpSecurity === 'string' ? JSON.parse(device.snmpSecurity) : device.snmpSecurity
        base.snmpUsername = sec.username || ''
        base.snmpAuthPassword = canSeePassword.value ? (sec.authPassword || '') : ''
        base.snmpPrivPassword = canSeePassword.value ? (sec.privPassword || '') : ''
      } catch (_) {
        base.snmpUsername = base.snmpAuthPassword = base.snmpPrivPassword = ''
      }
    } else {
      base.snmpUsername = base.snmpAuthPassword = base.snmpPrivPassword = ''
    }
    if (!canSeePassword.value) base.sshPassword = ''
    form.value = base
  } else {
    form.value = {
      name: '', type: 'server', ip: '', sshPort: 22, sshUser: '', sshPassword: '',
      snmpCommunity: '', snmpVersion: 'v2c', snmpUsername: '', snmpAuthPassword: '', snmpPrivPassword: '',
      vendor: '', model: '', remark: '', groupName: '',
    }
  }
  showModal.value = true
}

/** 校验管理 IP 是否为规范 IPv4（四段 0～255） */
function isValidIpv4(ip) {
  if (ip == null || String(ip).trim() === '') return false
  const s = String(ip).trim()
  const part = '(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)'
  const regex = new RegExp(`^${part}\\.${part}\\.${part}\\.${part}$`)
  return regex.test(s)
}

/** 提交添加/编辑：非管理员可修改密码但不可查看；留空则不修改原密码。IP 须为规范 IPv4 才能写入。 */
function submit() {
  const rawIp = form.value.ip
  if (rawIp == null || String(rawIp).trim() === '') {
    ipError.value = '请填写管理 IP'
    return
  }
  if (!isValidIpv4(rawIp)) {
    ipError.value = '请填写规范的 IPv4 地址（四段数字，每段 0～255，如 192.168.1.1）'
    return
  }
  ipError.value = ''
  const payload = { ...form.value }
  payload.ip = String(payload.ip || '').trim()
  const noPasswordView = editingId.value && !canSeePassword.value
  if (noPasswordView) {
    if (payload.sshPassword == null || String(payload.sshPassword).trim() === '') {
      delete payload.sshPassword
    }
  }
  if (payload.snmpVersion === 'v3') {
    if (noPasswordView && editingDevice.value?.snmpSecurity) {
      try {
        const existing = typeof editingDevice.value.snmpSecurity === 'string'
          ? JSON.parse(editingDevice.value.snmpSecurity) : editingDevice.value.snmpSecurity
        payload.snmpSecurity = JSON.stringify({
          username: payload.snmpUsername || existing?.username || '',
          authPassword: (payload.snmpAuthPassword != null && String(payload.snmpAuthPassword).trim() !== '')
            ? payload.snmpAuthPassword : (existing?.authPassword || ''),
          privPassword: (payload.snmpPrivPassword != null && String(payload.snmpPrivPassword).trim() !== '')
            ? payload.snmpPrivPassword : (existing?.privPassword || ''),
        })
      } catch (_) {
        payload.snmpSecurity = JSON.stringify({
          username: payload.snmpUsername || '',
          authPassword: payload.snmpAuthPassword || '',
          privPassword: payload.snmpPrivPassword || '',
        })
      }
    } else {
      payload.snmpSecurity = JSON.stringify({
        username: payload.snmpUsername || '',
        authPassword: payload.snmpAuthPassword || '',
        privPassword: payload.snmpPrivPassword || '',
      })
    }
    payload.snmpCommunity = null
  } else {
    payload.snmpSecurity = null
  }
  delete payload.snmpUsername
  delete payload.snmpAuthPassword
  delete payload.snmpPrivPassword
  const api = editingId.value ? updateDevice(editingId.value, payload) : createDevice(payload)
  api.then(() => { showModal.value = false; load() }).catch(e => showToast(e.response?.data?.message || '保存失败', 'error'))
}

function doDelete(id) {
  if (!confirm('确定删除该设备？')) return
  deleteDevice(id).then(load).catch(e => showToast(e.response?.data?.message || '删除失败', 'error'))
}

/** 未配置名称的 Linux 服务器：类型为服务器且名称为空（用于显示「获取主机名」按钮，且在线时才有意义） */
function isLinuxWithoutName(d) {
  if (!d || d.type !== 'server') return false
  const name = d.name == null ? '' : String(d.name).trim()
  return name === ''
}

function doFetchHostname(id) {
  fetchHostnameId.value = id
  fetchDeviceHostname(id)
    .then(r => {
      showToast(r.data?.message || '已从主机获取并更新设备名称', 'success')
      load()
    })
    .catch(e => showToast(e.response?.data?.message || '获取主机名失败', 'error'))
    .finally(() => { fetchHostnameId.value = null })
}

/** 对指定设备执行 Ping（后端会更新设备状态），刷新列表后再提示。请求中禁用 Ping 按钮，防止误触多次弹窗。 */
function doPing(id) {
  if (pingId.value) return
  pingId.value = id
  pingDevice(id)
    .then(async r => {
      const s = r.data || {}
      await load()
      const up = s.status === 'up'
      showToast(up ? `RTT: ${s.rttMs ?? '—'} ms` : '设备离线', up ? 'success' : 'warning')
    })
    .catch(() => showToast('Ping 失败', 'error'))
    .finally(() => { pingId.value = null })
}

onMounted(() => {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.USER)
    if (raw) {
      const user = JSON.parse(raw)
      userRoles.value = user.roles || []
    }
  } catch (_) {
    userRoles.value = []
  }
  load() // 首屏加载设备列表与分组
})
</script>

<style scoped>
.device-list.page { display: flex; flex-direction: column; gap: 1.25rem; }

/* ---------- 页面头部 ---------- */
.page-header {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; flex-wrap: wrap;
}
.header-left { flex: 1; min-width: 0; display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; }
.page-title { margin: 0; font-size: 1.375rem; font-weight: 600; color: #0f172a; letter-spacing: -0.02em; }
.page-desc { margin: 0; font-size: 0.875rem; color: #64748b; }
.header-actions { display: flex; align-items: center; gap: 0.75rem; flex-shrink: 0; flex-wrap: wrap; }
.header-filter { display: flex; align-items: center; gap: 0.5rem; }
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
  display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
  padding: 0.75rem 1.25rem; border-radius: 12px; background: #fff; border: 1px solid #e2e8f0;
}
.toolbar-group { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
.btn-tool {
  padding: 0.45rem 0.9rem; font-size: 0.8125rem; font-weight: 500;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; color: #475569;
  cursor: pointer; transition: background 0.2s, border-color 0.2s;
}
.btn-tool:hover:not(:disabled) { background: #f1f5f9; border-color: #cbd5e1; color: #334155; }
.btn-tool:disabled { opacity: 0.6; cursor: not-allowed; }
.toolbar-divider { width: 1px; height: 24px; background: #e2e8f0; }
.toolbar-filter { display: flex; align-items: center; gap: 0.5rem; }
.filter-label { font-size: 0.8125rem; font-weight: 500; color: #64748b; }
.filter-select {
  padding: 0.45rem 0.75rem; font-size: 0.8125rem;
  border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; color: #334155;
}

/* ---------- 表格卡片 ---------- */
.table-card.card {
  border-radius: 12px; overflow: hidden; border: 1px solid #e2e8f0; background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.table-scroll { overflow-x: auto; }
.device-table { border-collapse: collapse; width: 100%; min-width: 900px; }
.device-table th,
.device-table td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.device-table th {
  background: #f8fafc; font-weight: 600; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; color: #64748b;
}
.device-table tbody tr { transition: background 0.12s; }
.device-table tbody tr:hover td { background: #fafbfc; }
.device-table tbody tr.selected td { background: #f0fdf4; }
.col-check { width: 40px; text-align: center; }
.col-check input[type="checkbox"] { cursor: pointer; width: 16px; height: 16px; accent-color: #0ea5e9; }
.col-name { min-width: 120px; }
.name-text { font-weight: 600; color: #1e293b; }
.col-ip .ip-code {
  display: inline-block; padding: 0.2rem 0.5rem; font-size: 0.8125rem; font-family: ui-monospace, monospace;
  background: #fef2f2; color: #b91c1c; border-radius: 6px; font-variant-numeric: tabular-nums;
}
.col-vendor { white-space: nowrap; min-width: 5em; text-align: center; }
.col-type { white-space: nowrap; min-width: 5.5em; text-align: center; }
.th-vendor { min-width: 5em; text-align: center; }
.tag {
  display: inline-block; padding: 0.2rem 0.5rem; border-radius: 6px; font-size: 0.75rem; font-weight: 500;
}
.tag-vendor { background: #fef2f2; color: #b91c1c; min-width: 3em; text-align: center; }
.tag-type { background: #f1f5f9; color: #475569; min-width: 4em; text-align: center; }
.col-group { font-size: 0.8125rem; color: #64748b; }
.col-remark { font-size: 0.8125rem; color: #64748b; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.th-remark { min-width: 80px; }
.col-status { }
.status-badge {
  display: inline-flex; align-items: center; padding: 0.2rem 0.6rem; border-radius: 9999px;
  font-size: 0.75rem; font-weight: 500;
}
.status-badge.status-normal { background: #d1fae5; color: #047857; }
.status-badge.status-warning,
.status-badge.status-critical { background: #fef3c7; color: #b45309; }
.status-badge.status-offline { background: #f1f5f9; color: #64748b; }
.col-ops { white-space: nowrap; vertical-align: middle; }
.ops-buttons {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: nowrap;
}
.op-btn {
  flex: 0 0 auto;
  min-width: 4.25em;
  padding: 0.4rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 500;
  border-radius: 6px;
  border: 1px solid;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
  text-align: center;
}
.op-btn.op-fetch-hostname { background: #fef3c7; border-color: #fcd34d; color: #b45309; }
.op-btn.op-fetch-hostname:hover:not(:disabled) { background: #fde68a; box-shadow: 0 1px 4px rgba(180, 83, 9, 0.2); }
.op-btn.op-edit { background: #f0f9ff; border-color: #bae6fd; color: #0284c7; }
.op-btn.op-edit:hover { background: #e0f2fe; box-shadow: 0 1px 4px rgba(2, 132, 199, 0.2); }
.op-btn.op-ping { background: #f0fdf4; border-color: #bbf7d0; color: #16a34a; }
.op-btn.op-ping:hover:not(:disabled) { background: #dcfce7; box-shadow: 0 1px 4px rgba(22, 163, 74, 0.2); }
.op-btn:disabled { opacity: 0.65; cursor: not-allowed; }
.op-btn.op-delete { background: #fef2f2; border-color: #fecaca; color: #dc2626; }
.op-btn.op-delete:hover { background: #fee2e2; box-shadow: 0 1px 4px rgba(220, 38, 38, 0.2); }

/* 空状态 */
.empty-cell { padding: 0 !important; vertical-align: middle !important; }
.empty-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 3rem 1.5rem; text-align: center;
}
.empty-icon { font-size: 2rem; color: #cbd5e1; margin-bottom: 0.75rem; }
.empty-text { margin: 0 0 0.25rem; font-size: 0.9375rem; font-weight: 500; color: #64748b; }
.empty-hint { margin: 0; font-size: 0.8125rem; color: #94a3b8; }

/* 分页栏 */
.pagination-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap;
  padding: 0.6rem 1rem; border-top: 1px solid #e2e8f0; background: #f8fafc; font-size: 0.8125rem; color: #64748b;
}
.pagination-info { font-weight: 500; }
.pagination-btns { display: flex; gap: 0.5rem; }
.pagination-btns .op-btn {
  padding: 0.35rem 0.75rem; font-size: 0.8125rem;
  border: 1px solid #cbd5e1; border-radius: 6px; background: #fff; color: #475569; cursor: pointer;
}
.pagination-btns .op-btn:hover:not(:disabled) { background: #f1f5f9; border-color: #94a3b8; }
.pagination-btns .op-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 批量操作栏 */
.batch-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem;
  padding: 0.65rem 1.25rem; background: linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%);
  border-top: 1px solid #a7f3d0; font-size: 0.875rem; font-weight: 500; color: #065f46;
}
.btn-danger-sm {
  padding: 0.4rem 0.85rem; font-size: 0.8125rem; font-weight: 500;
  background: #dc2626; border: none; border-radius: 8px; color: #fff; cursor: pointer;
  transition: background 0.2s, box-shadow 0.2s;
}
.btn-danger-sm:hover { background: #b91c1c; box-shadow: 0 2px 6px rgba(220, 38, 38, 0.3); }

.th-sortable { cursor: pointer; user-select: none; white-space: nowrap; }
.th-sortable:hover { background: #f1f5f9; color: #475569; }
.sort-arrow { margin-left: 0.2rem; font-size: 0.7rem; color: #94a3b8; }
.th-name { min-width: 110px; }

/* ---------- 弹窗 ---------- */
/* ---------- 居中提示弹窗（中文优化） ---------- */
.toast-overlay {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(15, 23, 42, 0.45); backdrop-filter: blur(6px);
  display: flex; align-items: center; justify-content: center; padding: 1.25rem;
}
.toast-card {
  min-width: 300px; max-width: 420px;
  padding: 1.5rem 1.75rem 1.5rem; border-radius: 16px;
  background: #fff; box-shadow: 0 24px 48px rgba(0, 0, 0, 0.12), 0 0 0 1px rgba(0, 0, 0, 0.04);
  border: none; text-align: center;
  font-family: "PingFang SC", "Microsoft YaHei", "Hiragino Sans GB", "Helvetica Neue", sans-serif;
}
.toast-card.toast-success { border-top: 3px solid #22c55e; }
.toast-card.toast-warning { border-top: 3px solid #f59e0b; }
.toast-card.toast-error { border-top: 3px solid #ef4444; }
.toast-card.toast-info { border-top: 3px solid #3b82f6; }
.toast-title {
  display: flex; align-items: center; justify-content: center; gap: 0.5rem;
  margin-bottom: 0.75rem; font-size: 0.8125rem; font-weight: 600; letter-spacing: 0.05em; color: #64748b;
}
.toast-dot {
  width: 6px; height: 6px; border-radius: 50%;
}
.toast-success .toast-dot { background: #22c55e; }
.toast-warning .toast-dot { background: #f59e0b; }
.toast-error .toast-dot { background: #ef4444; }
.toast-info .toast-dot { background: #3b82f6; }
.toast-message {
  margin: 0 0 1.35rem; font-size: 1rem; line-height: 1.65; color: #1e293b;
  white-space: pre-wrap; word-break: break-word; letter-spacing: 0.02em;
}
.toast-btn {
  display: block; width: 100%; padding: 0.6rem 1.25rem; font-size: 0.9375rem; font-weight: 500;
  background: linear-gradient(180deg, #0ea5e9 0%, #0284c7 100%); color: #fff;
  border: none; border-radius: 10px; cursor: pointer; box-shadow: 0 2px 8px rgba(14, 165, 233, 0.35);
  transition: box-shadow 0.2s, transform 0.05s;
}
.toast-btn:hover { box-shadow: 0 4px 12px rgba(14, 165, 233, 0.45); }
.toast-btn:active { transform: scale(0.98); }
.toast-fade-enter-active, .toast-fade-leave-active { transition: opacity 0.22s ease; }
.toast-fade-enter-from, .toast-fade-leave-to { opacity: 0; }
.toast-fade-enter-active .toast-card, .toast-fade-leave-active .toast-card { transition: transform 0.22s ease; }
.toast-fade-enter-from .toast-card, .toast-fade-leave-to .toast-card { transform: scale(0.96); }

.modal-overlay {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(15, 23, 42, 0.45); backdrop-filter: blur(6px);
  display: flex; align-items: center; justify-content: center; padding: 1rem;
}
.modal-dialog {
  width: 100%; max-width: 520px; max-height: 90vh; overflow: hidden; display: flex; flex-direction: column;
  border-radius: 16px; box-shadow: 0 24px 48px rgba(0, 0, 0, 0.12);
}
.modal-dialog .modal-form { overflow-y: auto; padding: 0 1.5rem 1.5rem; }
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 1.25rem 1.5rem; border-bottom: 1px solid #f1f5f9;
}
.modal-header h3 { margin: 0; font-size: 1.125rem; font-weight: 600; color: #0f172a; }
.modal-close {
  width: 32px; height: 32px; padding: 0; font-size: 1.25rem; line-height: 1;
  background: transparent; border: none; border-radius: 8px; color: #64748b; cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.modal-close:hover { background: #f1f5f9; color: #334155; }

.admin-hint { margin: 0 1.5rem 1rem; padding: 0.6rem 0.85rem; font-size: 0.8125rem; border-radius: 8px; }
.admin-hint strong { font-weight: 600; }
.admin-hint.admin-ok { background: #ecfdf5; color: #065f46; border: 1px solid #a7f3d0; }
.admin-hint.admin-lock { background: #f8fafc; color: #64748b; border: 1px solid #e2e8f0; }

.modal-form .form-section { margin-bottom: 1.25rem; }
.form-section-title {
  margin: 0 0 0.75rem; font-size: 0.8125rem; font-weight: 600; color: #64748b; text-transform: uppercase; letter-spacing: 0.04em;
}
.form-group { margin-bottom: 1rem; }
.form-group:last-child { margin-bottom: 0; }
.form-group label { display: block; margin-bottom: 0.35rem; font-size: 0.8125rem; font-weight: 500; color: #475569; }
.password-wrap { position: relative; display: inline-block; width: 100%; max-width: 320px; }
.password-wrap input { width: 100%; padding-right: 2.25rem; box-sizing: border-box; }
.pw-toggle {
  position: absolute; right: 0.5rem; top: 50%; transform: translateY(-50%);
  width: 28px; height: 28px; padding: 0; border: none; background: transparent; border-radius: 6px;
  color: #64748b; cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: color 0.2s, background 0.2s;
}
.pw-toggle:hover { color: #0ea5e9; background: #f0f9ff; }
.pw-toggle.visible { color: #0ea5e9; }
.pw-icon { width: 18px; height: 18px; pointer-events: none; }

.form-group input,
.form-group select,
.form-group textarea {
  width: 100%; padding: 0.5rem 0.75rem; font-size: 0.875rem;
  border: 1px solid #e2e8f0; border-radius: 8px; background: #fff;
}
.form-group input:focus,
.form-group select:focus,
.form-group textarea:focus {
  outline: none; border-color: #0ea5e9; box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.12);
}
.form-row { display: flex; gap: 1rem; }
.form-row-2 .form-group { flex: 1; min-width: 0; }
.form-group.form-hint .hint { margin: 0.25rem 0 0; font-size: 0.8125rem; color: #64748b; }
.form-hint-inline { display: block; margin-top: 0.25rem; font-size: 0.75rem; color: #64748b; }
.form-error { margin: 0.25rem 0 0; font-size: 0.75rem; color: #dc2626; }
.form-group input.input-invalid { border-color: #f87171; background: #fef2f2; }
.input-with-hint { display: flex; align-items: center; gap: 0.5rem; flex-wrap: nowrap; }
.input-with-hint input { flex: 0 0 auto; width: 6rem; }
.input-with-hint .form-hint-inline { margin-top: 0; flex: 0 0 auto; white-space: nowrap; }
.form-actions {
  display: flex; justify-content: flex-end; gap: 0.75rem; margin-top: 1.5rem; padding-top: 1.25rem; border-top: 1px solid #f1f5f9;
}
.form-actions .btn-secondary {
  padding: 0.5rem 1rem; font-size: 0.875rem; font-weight: 500;
  background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 8px; color: #475569; cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
}
.form-actions .btn-secondary:hover { background: #e2e8f0; border-color: #cbd5e1; }
.form-actions .btn-primary { min-width: 88px; }

.modal-import .import-hint { margin: 0 1.5rem 1rem; font-size: 0.8125rem; color: #64748b; }
.modal-import .import-hint strong { color: #475569; }
.import-body { display: flex; flex-direction: column; padding: 0 1.5rem 1.5rem; }
.modal-import .form-actions { margin: 0 1.5rem 1.5rem; padding-top: 1rem; }

/* 批量添加 Excel 表格式 */
.modal-import-table { max-width: 640px; }
.import-table-wrap { overflow-x: auto; margin-bottom: 0.75rem; border: 1px solid #e2e8f0; border-radius: 8px; }
.import-table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }
.import-table th { text-align: left; padding: 0.5rem 0.6rem; background: #f8fafc; color: #475569; font-weight: 600; border-bottom: 1px solid #e2e8f0; }
.import-table td { padding: 0.35rem 0.5rem; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.import-table tbody tr:last-child td { border-bottom: none; }
.import-cell { width: 100%; min-width: 0; padding: 0.4rem 0.5rem; font-size: 0.8125rem; border: 1px solid #e2e8f0; border-radius: 6px; box-sizing: border-box; }
.import-cell:focus { outline: none; border-color: #0ea5e9; }
.import-select { cursor: pointer; background: #fff; }
.col-import-op { width: 56px; text-align: center; }
.op-delete-sm { padding: 0.25rem 0.5rem; font-size: 0.75rem; color: #64748b; background: transparent; border: none; cursor: pointer; border-radius: 4px; }
.op-delete-sm:hover { color: #dc2626; background: #fef2f2; }
.btn-add-row { align-self: flex-start; margin-bottom: 1rem; padding: 0.4rem 0.75rem; font-size: 0.8125rem; color: #0ea5e9; background: #f0f9ff; border: 1px dashed #7dd3fc; border-radius: 6px; cursor: pointer; }
.btn-add-row:hover { background: #e0f2fe; color: #0284c7; }
</style>
