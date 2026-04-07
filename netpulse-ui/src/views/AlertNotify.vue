<template>
  <div class="page alert-page">
    <div class="card intro-card">
      <h3>告警说明</h3>
      <p class="intro-hint">
        <strong>何时会产生告警？</strong><br/>
        · <strong>指标类</strong>（CPU/内存/磁盘）：系统每 <strong>5 分钟</strong>根据「实时指标」页的采集结果评估一次。<br/>
        · <strong>设备上下线</strong>：在设备状态由在线→离线或离线→在线变化时<strong>立即</strong>产生一条告警（由监控轮询检测到状态变化时写入）。
      </p>
      <div class="level-list">
        <div class="level-item level-critical">
          <span class="level-num">一级</span>
          <span class="level-name">严重</span>
          <span class="level-desc">资源严重异常，需立即处理</span>
        </div>
        <div class="level-item level-warning">
          <span class="level-num">二级</span>
          <span class="level-name">警告</span>
          <span class="level-desc">超过阈值，需关注</span>
        </div>
        <div class="level-item level-info">
          <span class="level-num">三级</span>
          <span class="level-name">一般 / 提示</span>
          <span class="level-desc">设备上线/下线等提示类通知</span>
        </div>
      </div>
    </div>

    <div class="card table-loading-wrap">
      <div v-if="rulesLoading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <div class="card-head">
        <h3>告警规则</h3>
        <div>
          <button class="primary small" @click="openRuleForm()">添加规则</button>
          <button class="secondary small" @click="doExportHistory">导出告警历史 CSV</button>
        </div>
      </div>
      <div class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>告警类型</th>
              <th>条件</th>
              <th>设备类型</th>
              <th>等级</th>
              <th>启用</th>
              <th>邮件</th>
              <th>自动修复</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rules" :key="r.id">
              <td>{{ r.name }}</td>
              <td>{{ metricKeyLabel(r.metricKey) }}</td>
              <td>{{ r.condition }}</td>
              <td>{{ deviceTypesLabel(r.deviceTypes) }}</td>
              <td><span :class="['badge', 'severity-' + r.severity]">{{ severityLabel(r.severity) }}</span></td>
              <td>{{ r.enabled ? '是' : '否' }}</td>
              <td>{{ r.notifyEmail ? '是' : '否' }}</td>
              <td>{{ r.autoFixEnabled ? (r.autoFixType === 'ssh_command' ? 'SSH' : '本地') : '否' }}</td>
              <td>
                <button class="small" @click="openRuleForm(r)">编辑</button>
                <button class="danger small" @click="doDeleteRule(r.id)">删除</button>
              </td>
            </tr>
            <tr v-if="!rules.length"><td colspan="9" class="empty">暂无告警规则，点击「添加规则」创建</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card table-loading-wrap">
      <div v-if="historyLoading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <h3>告警历史</h3>
      <div class="history-toolbar">
        <span>状态</span>
        <select v-model="historyFilterStatus" class="filter-select">
          <option value="">全部</option>
          <option value="firing">告警中</option>
          <option value="resolved">已恢复</option>
        </select>
        <span>严重程度</span>
        <select v-model="historyFilterSeverity" class="filter-select">
          <option value="">全部</option>
          <option value="critical">一级·严重</option>
          <option value="warning">二级·警告</option>
          <option value="info">三级·一般</option>
        </select>
        <button type="button" class="primary small" @click="loadHistory(0)">查询</button>
      </div>
      <div v-if="selectedHistoryIds.length > 0" class="batch-bar">
        <span>已选 {{ selectedHistoryIds.length }} 条</span>
        <button type="button" class="primary small" @click="doBatchResolve" :disabled="batchResolveLoading">
          {{ batchResolveLoading ? '处理中…' : '批量标记已处理' }}
        </button>
        <button type="button" class="small" @click="selectedHistoryIds = []">取消选择</button>
      </div>
      <div class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th class="col-check">
                <input
                  type="checkbox"
                  :checked="historyFiringIds.length > 0 && selectedHistoryIds.length === historyFiringIds.length"
                  :indeterminate.prop="selectedHistoryIds.length > 0 && selectedHistoryIds.length < historyFiringIds.length"
                  @change="toggleAllHistorySelection"
                />
              </th>
              <th>时间</th>
              <th>规则ID</th>
              <th>设备ID</th>
              <th>状态</th>
              <th>严重程度</th>
              <th>消息</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="h in history" :key="h.id">
              <td class="col-check">
                <input
                  v-if="h.status === 'firing'"
                  type="checkbox"
                  :checked="selectedHistoryIds.includes(h.id)"
                  @change="toggleHistorySelection(h.id)"
                />
              </td>
              <td>{{ formatTime(h.startTime) }}</td>
              <td>{{ h.ruleId }}</td>
              <td>{{ h.deviceId }}</td>
              <td><span :class="['badge', 'status-' + h.status]">{{ statusLabel(h.status) }}</span></td>
              <td><span :class="['badge', 'severity-' + h.severity]">{{ severityLabel(h.severity) }}</span></td>
              <td>{{ h.message || '-' }}</td>
              <td>
                <button
                  v-if="h.status === 'firing'"
                  class="small"
                  @click="doResolve(h)"
                  :disabled="resolveLoadingId === h.id"
                >
                  {{ resolveLoadingId === h.id ? '处理中…' : '标记已处理' }}
                </button>
              </td>
            </tr>
            <tr v-if="!history.length"><td colspan="8" class="empty">暂无告警历史</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="historyTotalPages > 0" class="pagination">
        <button type="button" class="small" :disabled="historyPage <= 0" @click="loadHistory(historyPage - 1)">上一页</button>
        <span class="page-info">第 {{ historyPage + 1 }} / {{ historyTotalPages }} 页，共 {{ historyTotalElements }} 条</span>
        <button type="button" class="small" :disabled="historyPage >= historyTotalPages - 1" @click="loadHistory(historyPage + 1)">下一页</button>
      </div>
    </div>
    <div v-if="showRuleModal" class="modal-overlay" @click.self="showRuleModal = false">
      <div class="modal card">
        <h3>{{ editingRuleId ? '编辑规则' : '添加规则' }}</h3>
        <form @submit.prevent="submitRule">
          <div class="form-group">
            <label>告警类型</label>
            <select v-model="ruleType" @change="onRuleTypeChange">
              <option value="metric">指标告警（CPU/内存/磁盘等）</option>
              <option value="device_status">设备上下线（三级·一般/提示）</option>
            </select>
          </div>
          <div class="form-group">
            <label>从模板加载</label>
            <select v-model="selectedTemplateId" @change="onTemplateChange">
              <option value="">不使用模板</option>
              <option v-for="t in templates" :key="t.id" :value="t.id">
                {{ t.name }}（{{ metricKeyLabel(t.metricKey) }}）
              </option>
            </select>
          </div>
          <div class="form-group">
            <label>名称</label>
            <input v-model="ruleForm.name" required :placeholder="ruleType === 'device_status' ? '如：设备离线通知' : '规则名称'" />
          </div>
          <div class="form-group">
            <label>设备类型</label>
            <select v-model="ruleForm.deviceTypes">
              <option value="">全选（所有类型）</option>
              <option value="server">服务器</option>
              <option value="firewall">防火墙</option>
              <option value="router">路由器</option>
              <option value="switch">交换机</option>
              <option value="other">其他</option>
            </select>
          </div>
          <template v-if="ruleType === 'metric'">
            <div class="form-group">
              <label>指标</label>
              <select v-model="ruleForm.metricKey">
                <option v-for="opt in metricOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
            <div class="form-group condition-row">
              <label class="condition-label">条件</label>
              <div class="condition-inputs">
                <select v-model="ruleForm.conditionOperator" class="condition-operator">
                  <option v-for="o in conditionOperators" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
                <input
                  v-model.number="ruleForm.conditionValue"
                  type="number"
                  min="0"
                  max="100"
                  step="1"
                  class="condition-value"
                  placeholder="0–100"
                />
              </div>
            </div>
            <div class="form-group">
              <label>等级</label>
              <select v-model="ruleForm.severity">
                <option value="critical">一级·严重</option>
                <option value="warning">二级·警告</option>
              </select>
            </div>
          </template>
          <template v-else>
            <div class="form-group">
              <label>事件</label>
              <select v-model="ruleForm.condition">
                <option value="offline">设备离线时通知</option>
                <option value="online">设备上线时通知</option>
              </select>
            </div>
          </template>
          <div class="form-group">
            <label><input type="checkbox" v-model="ruleForm.enabled" /> 启用</label>
          </div>
          <div class="form-group">
            <label><input type="checkbox" v-model="ruleForm.notifyEmail" /> 邮件通知</label>
          </div>
          <div class="form-group auto-fix-section">
            <label><input type="checkbox" v-model="ruleForm.autoFixEnabled" /> 告警时自动执行修复脚本</label>
          </div>
          <template v-if="ruleForm.autoFixEnabled">
            <div class="form-group">
              <label>修复方式</label>
              <select v-model="ruleForm.autoFixType">
                <option value="ssh_command">SSH 命令（在告警设备上执行）</option>
                <option value="local_script">本地脚本（在监控服务器上执行）</option>
              </select>
            </div>
            <div class="form-group">
              <label>{{ ruleForm.autoFixType === 'ssh_command' ? '修复命令' : '脚本路径或命令' }}</label>
              <textarea
                v-model="ruleForm.autoFixCommand"
                rows="3"
                :placeholder="ruleForm.autoFixType === 'ssh_command' ? '如：systemctl restart nginx 或 /opt/scripts/clear_cache.sh' : '如：/opt/netpulse/scripts/restart_service.sh'"
              ></textarea>
              <p class="form-hint">SSH 方式需设备已配置 SSH 用户名与密码；本地脚本在监控运维系统所在服务器执行。</p>
            </div>
          </template>
          <div class="form-actions">
            <button type="button" @click="showRuleModal = false" :disabled="submitRuleLoading">取消</button>
            <button type="submit" class="primary" :disabled="submitRuleLoading">{{ submitRuleLoading ? '保存中…' : '保存' }}</button>
            <button type="button" class="secondary" @click="saveAsTemplate" :disabled="submitRuleLoading">保存为模板</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import {
  getAlertRules, getAlertHistory, resolveAlert, batchResolveAlerts,
  createAlertRule, updateAlertRule, deleteAlertRule,
  getAlertTemplates, createAlertTemplate,
} from '../api/alerts'
import { exportAlertHistory } from '../api/export'
import { getApiErrorHint, isUnauthorizedError } from '../api/request'
import { formatTime } from '../utils/systemTimezone'

const rules = ref([])
const rulesLoading = ref(false)
const history = ref([])
const historyLoading = ref(false)
const historyPage = ref(0)
const historyTotalPages = ref(0)
const historyTotalElements = ref(0)
const historyPageSize = 15
const historyFilterStatus = ref('')
const historyFilterSeverity = ref('')
const showRuleModal = ref(false)
const submitRuleLoading = ref(false)
const resolveLoadingId = ref(null)
const selectedHistoryIds = ref([])
const batchResolveLoading = ref(false)
const editingRuleId = ref(null)
const ruleType = ref('metric') // 'metric' | 'device_status'
const metricOptions = [
  { value: 'cpu_usage', label: 'CPU 使用率 (%)' },
  { value: 'mem_usage', label: '内存使用率 (%)' },
  { value: 'disk_usage', label: '磁盘使用率 (%)' },
]
const ruleForm = ref({
  name: '', metricKey: 'cpu_usage', condition: '', severity: 'warning',
  conditionOperator: '>', conditionValue: 80,
  deviceTypes: '', enabled: true, notifyEmail: false, deviceId: null, monitorItemId: null,
  autoFixEnabled: false, autoFixType: 'ssh_command', autoFixCommand: '',
})

const conditionOperators = [
  { value: '>', label: '大于 (>)' },
  { value: '=', label: '等于 (=)' },
  { value: '<', label: '小于 (<)' },
]

function parseCondition(cond) {
  if (!cond || typeof cond !== 'string') return { operator: '>', value: 80 }
  const m = cond.trim().match(/^\s*([><]=?|=)\s*(\d+(?:\.\d+)?)\s*$/)
  if (!m) return { operator: '>', value: 80 }
  let op = m[1]
  if (op === '>=') op = '>'
  if (op === '<=') op = '<'
  const val = Math.min(100, Math.max(0, parseFloat(m[2]) || 80))
  return { operator: op, value: val }
}

const templates = ref([])
const selectedTemplateId = ref('')

/** 当前页中状态为告警中的历史 id 列表，用于批量勾选 */
const historyFiringIds = computed(() => history.value.filter(h => h.status === 'firing').map(h => h.id))

function toggleHistorySelection(id) {
  const idx = selectedHistoryIds.value.indexOf(id)
  if (idx >= 0) selectedHistoryIds.value = selectedHistoryIds.value.filter(x => x !== id)
  else selectedHistoryIds.value = [...selectedHistoryIds.value, id]
}

function toggleAllHistorySelection() {
  if (selectedHistoryIds.value.length === historyFiringIds.value.length) selectedHistoryIds.value = []
  else selectedHistoryIds.value = [...historyFiringIds.value]
}

function doBatchResolve() {
  if (!selectedHistoryIds.value.length) return
  if (!window.confirm('确定将所选 ' + selectedHistoryIds.value.length + ' 条告警标记为已处理吗？')) return
  batchResolveLoading.value = true
  batchResolveAlerts(selectedHistoryIds.value)
    .then(r => {
      const msg = (r.data && r.data.message) ? r.data.message : '已批量处理'
      selectedHistoryIds.value = []
      loadHistory(historyPage.value)
      alert(msg)
    })
    .catch(e => {
      const msg = e.response?.data?.message ?? e.message ?? '批量处理失败，请稍后重试'
      alert(msg)
    })
    .finally(() => { batchResolveLoading.value = false })
}

function metricKeyLabel(key) {
  if (!key) return '-'
  const o = metricOptions.find(x => x.value === key)
  if (o) return o.label
  if (key === 'device_status') return '设备上下线'
  return key
}

function severityLabel(s) {
  const m = { critical: '一级·严重', warning: '二级·警告', info: '三级：一般/提示' }
  return m[s] || s || '-'
}

function statusLabel(s) {
  const m = { firing: '告警中', resolved: '已恢复' }
  return m[s] || s || '-'
}

function deviceTypesLabel(v) {
  if (v == null || String(v).trim() === '') return '全选'
  const m = { server: '服务器', firewall: '防火墙', router: '路由器', switch: '交换机', other: '其他' }
  const parts = String(v).split(',').map(s => m[s.trim()] || s.trim()).filter(Boolean)
  return parts.length ? parts.join('、') : '全选'
}


function onRuleTypeChange() {
  if (ruleType.value === 'device_status') {
    ruleForm.value.metricKey = 'device_status'
    ruleForm.value.condition = 'offline'
    ruleForm.value.severity = 'info'
  } else {
    ruleForm.value.metricKey = ruleForm.value.metricKey || 'cpu_usage'
    if (ruleForm.value.metricKey === 'device_status') ruleForm.value.metricKey = 'cpu_usage'
    const { operator, value } = parseCondition(ruleForm.value.condition)
    ruleForm.value.conditionOperator = operator
    ruleForm.value.conditionValue = value
    ruleForm.value.severity = ruleForm.value.severity || 'warning'
  }
}

function openRuleForm(rule = null) {
  editingRuleId.value = rule ? rule.id : null
  selectedTemplateId.value = ''
  if (rule) {
    ruleType.value = rule.metricKey === 'device_status' ? 'device_status' : 'metric'
    ruleForm.value = {
      ...rule,
      deviceTypes: rule.deviceTypes ?? '',
      notifyEmail: rule.notifyEmail === true,
      autoFixEnabled: rule.autoFixEnabled === true,
      autoFixType: rule.autoFixType || 'ssh_command',
      autoFixCommand: rule.autoFixCommand ?? '',
    }
    if (ruleType.value === 'device_status') {
      ruleForm.value.condition = rule.condition || 'offline'
      ruleForm.value.severity = 'info'
    } else {
      const { operator, value } = parseCondition(rule.condition)
      ruleForm.value.conditionOperator = operator
      ruleForm.value.conditionValue = value
    }
  } else {
    ruleType.value = 'metric'
    ruleForm.value = {
      name: '', metricKey: 'cpu_usage', condition: '> 80', severity: 'warning',
      conditionOperator: '>', conditionValue: 80,
      deviceTypes: '', enabled: true, notifyEmail: false, deviceId: null, monitorItemId: null,
      autoFixEnabled: false, autoFixType: 'ssh_command', autoFixCommand: '',
    }
  }
  showRuleModal.value = true
}

function onTemplateChange() {
  if (!selectedTemplateId.value) return
  const tpl = templates.value.find(t => t.id === selectedTemplateId.value || String(t.id) === String(selectedTemplateId.value))
  if (!tpl) return
  const metricKey = tpl.metricKey || 'cpu_usage'
  ruleType.value = metricKey === 'device_status' ? 'device_status' : 'metric'
  ruleForm.value.metricKey = metricKey
  ruleForm.value.condition = tpl.condition || (metricKey === 'device_status' ? 'offline' : '> 80')
  if (metricKey !== 'device_status') {
    const { operator, value } = parseCondition(ruleForm.value.condition)
    ruleForm.value.conditionOperator = operator
    ruleForm.value.conditionValue = value
  }
  ruleForm.value.severity = tpl.severity || (metricKey === 'device_status' ? 'info' : 'warning')
  ruleForm.value.deviceTypes = tpl.deviceTypes ?? ''
  if (!ruleForm.value.name) {
    ruleForm.value.name = tpl.name
  }
}

function loadHistory(page) {
  historyLoading.value = true
  selectedHistoryIds.value = []
  const params = { page: Math.max(0, page), size: historyPageSize }
  if (historyFilterStatus.value) params.status = historyFilterStatus.value
  if (historyFilterSeverity.value) params.severity = historyFilterSeverity.value
  getAlertHistory(params)
    .then(r => {
      const data = r.data
      history.value = (data && data.content) ? data.content : (Array.isArray(data) ? data : [])
      historyPage.value = data && typeof data.number === 'number' ? data.number : page
      historyTotalPages.value = data && typeof data.totalPages === 'number' ? data.totalPages : 0
      historyTotalElements.value = data && typeof data.totalElements === 'number' ? data.totalElements : 0
    })
    .catch((err) => {
      history.value = []
      historyTotalPages.value = 0
      historyTotalElements.value = 0
      if (isUnauthorizedError(err)) return
      alert(getApiErrorHint(err, '告警历史加载失败，请检查网络或后端服务'))
    })
    .finally(() => { historyLoading.value = false })
}

function load() {
  rulesLoading.value = true
  getAlertRules()
    .then(r => { rules.value = r.data != null ? r.data : [] })
    .catch((err) => {
      rules.value = []
      if (isUnauthorizedError(err)) return
      alert(getApiErrorHint(err, '告警规则加载失败，请检查网络或后端服务'))
    })
    .finally(() => { rulesLoading.value = false })
  loadHistory(0)
  getAlertTemplates()
    .then(r => { templates.value = r.data || [] })
    .catch((err) => {
      templates.value = []
      if (isUnauthorizedError(err)) return
      alert(getApiErrorHint(err, '告警模板加载失败，请检查网络或后端服务'))
    })
}

function submitRule() {
  const raw = ruleForm.value
  const conditionStr = ruleType.value === 'device_status'
    ? ((raw.condition || '').trim() || 'offline')
    : (raw.conditionOperator || '>') + ' ' + (Math.min(100, Math.max(0, Number(raw.conditionValue) || 80)))
  const payload = {
    name: (raw.name || '').trim(),
    metricKey: raw.metricKey || 'cpu_usage',
    condition: conditionStr,
    severity: raw.severity || 'warning',
    deviceTypes: (raw.deviceTypes != null && String(raw.deviceTypes).trim()) ? String(raw.deviceTypes).trim() : '',
    enabled: raw.enabled !== false,
    notifyEmail: raw.notifyEmail === true,
    deviceId: raw.deviceId || null,
    monitorItemId: raw.monitorItemId || null,
    autoFixEnabled: raw.autoFixEnabled === true,
    autoFixType: (raw.autoFixType || 'ssh_command').trim(),
    autoFixCommand: (raw.autoFixCommand || '').trim() || null,
  }
  if (ruleType.value === 'device_status') {
    payload.metricKey = 'device_status'
    payload.severity = 'info'
  }
  if (!payload.name) {
    alert('请填写规则名称')
    return
  }
  submitRuleLoading.value = true
  const api = editingRuleId.value ? updateAlertRule(editingRuleId.value, payload) : createAlertRule(payload)
  api
    .then(() => {
      showRuleModal.value = false
      load()
      alert(editingRuleId.value ? '规则已更新' : '规则已添加')
    })
    .catch(e => {
      const msg = e.response?.data?.message ?? e.message ?? '保存失败，请稍后重试'
      alert(msg)
    })
    .finally(() => { submitRuleLoading.value = false })
}

function doDeleteRule(id) {
  if (!confirm('确定删除该规则？')) return
  deleteAlertRule(id).then(load).catch(e => alert(e.response?.data?.message || '删除失败'))
}

function doExportHistory() {
  exportAlertHistory()
}

function saveAsTemplate() {
  const raw = ruleForm.value
  const metricKey = raw.metricKey || (ruleType.value === 'device_status' ? 'device_status' : 'cpu_usage')
  const condition = ruleType.value === 'device_status'
    ? ((raw.condition || '').trim() || 'offline')
    : (raw.conditionOperator || '>') + ' ' + (Math.min(100, Math.max(0, Number(raw.conditionValue) || 80)))
  const severity = raw.severity || (ruleType.value === 'device_status' ? 'info' : 'warning')
  const nameDefault = (raw.name || '').trim() || '新告警模板'
  const name = window.prompt('请输入模板名称：', nameDefault)
  if (!name) return
  const payload = {
    name: name.trim(),
    metricKey,
    condition,
    severity,
    deviceTypes: (raw.deviceTypes != null && String(raw.deviceTypes).trim()) ? String(raw.deviceTypes).trim() : '',
  }
  createAlertTemplate(payload).then(() => {
    getAlertTemplates().then(r => { templates.value = r.data || [] }).catch(() => { templates.value = [] })
    alert('模板已保存')
  }).catch(e => {
    const msg = e.response?.data?.message ?? e.message ?? '保存模板失败'
    alert(msg)
  })
}

function doResolve(h) {
  if (!h || !h.id) return
  if (!window.confirm('确定将该告警标记为已处理吗？')) return
  resolveLoadingId.value = h.id
  resolveAlert(h.id)
    .then(() => loadHistory(historyPage.value))
    .catch(e => {
      const msg = e.response?.data?.message ?? e.message ?? '处理失败，请稍后重试'
      alert(msg)
    })
    .finally(() => { resolveLoadingId.value = null })
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.alert-page .card { margin-bottom: 1.25rem; }
.intro-card { background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-color: #e2e8f0; }
.intro-card h3 { margin: 0 0 0.5rem 0; font-size: 1rem; color: #0f172a; }
.intro-hint { margin: 0; padding: 0.75rem 1rem; background: #eff6ff; border-radius: 8px; border-left: 4px solid #3b82f6; font-size: 0.8125rem; color: #1e40af; line-height: 1.6; }
.intro-hint strong { color: #1e3a8a; }
.intro-email-hint { margin: 0.5rem 0 0; font-size: 0.8125rem; color: #64748b; line-height: 1.5; }
.intro-email-hint strong { color: #334155; }
.level-item .level-desc em { font-style: italic; color: #b91c1c; }
.level-list { display: flex; gap: 1rem; flex-wrap: wrap; }
.level-item { flex: 1; min-width: 140px; padding: 0.75rem 1rem; border-radius: 10px; display: flex; flex-direction: column; gap: 0.25rem; }
.level-item .level-num { font-size: 0.75rem; font-weight: 600; color: #64748b; }
.level-item .level-name { font-size: 1rem; font-weight: 600; }
.level-item .level-desc { font-size: 0.75rem; color: #64748b; }
.level-critical { background: #fef2f2; border: 1px solid #fecaca; }
.level-critical .level-name { color: #b91c1c; }
.level-warning { background: #fffbeb; border: 1px solid #fde68a; }
.level-warning .level-name { color: #b45309; }
.level-info { background: #eff6ff; border: 1px solid #bfdbfe; }
.level-info .level-name { color: #1d4ed8; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.card-head h3 { margin: 0; }
.form-group { margin-bottom: 1rem; }
.form-group label { display: block; margin-bottom: 0.35rem; font-size: 0.875rem; }
.condition-row { display: flex; align-items: center; gap: 0.75rem; flex-wrap: nowrap; }
.condition-row .condition-label { margin-bottom: 0; flex-shrink: 0; }
.condition-row .condition-inputs { display: inline-flex; align-items: center; gap: 0.25rem; flex-wrap: nowrap; white-space: nowrap; }
.condition-operator { width: auto; min-width: 4.8em; padding: 0.5rem 0.5rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; flex-shrink: 0; }
.condition-value { width: 4em; padding: 0.5rem 0.5rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; box-sizing: border-box; flex-shrink: 0; text-align: center; }
.form-group select { width: 100%; max-width: 320px; padding: 0.5rem 0.75rem; }
.form-group textarea { width: 100%; max-width: 100%; padding: 0.5rem 0.75rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; box-sizing: border-box; }
.form-hint { margin: 0.35rem 0 0; font-size: 0.75rem; color: #64748b; }
.auto-fix-section { padding-top: 0.5rem; border-top: 1px solid #e2e8f0; }
.form-actions { display: flex; gap: 0.5rem; margin-top: 1rem; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.modal { max-width: 460px; width: 90%; }
.modal h3 { margin: 0 0 1rem; }
.badge.severity-critical { background: #fef2f2; color: #b91c1c; }
.badge.severity-warning { background: #fef3c7; color: #b45309; }
.badge.severity-info { background: #dbeafe; color: #1d4ed8; }
.badge.status-firing { background: #fee2e2; color: #b91c1c; }
.badge.status-resolved { background: #d1fae5; color: #047857; }
.badge.success { background: #d1fae5; color: #047857; }
.badge.fail { background: #fee2e2; color: #b91c1c; }
.history-toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; flex-wrap: wrap; }
.history-toolbar .filter-select { padding: 0.4rem 0.65rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; min-width: 120px; }
.history-toolbar .filter-input { padding: 0.4rem 0.65rem; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 0.875rem; min-width: 150px; }
.batch-bar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.75rem; padding: 0.5rem 0; }
.batch-bar span { font-size: 0.875rem; color: #64748b; }
.col-check { width: 2.5rem; text-align: center; vertical-align: middle; }
.col-check input { cursor: pointer; }
.pagination { margin-top: 1rem; display: flex; align-items: center; gap: 1rem; }
.page-info { font-size: 0.875rem; color: #64748b; }
.empty { text-align: center; color: #9ca3af; }
button.small { margin-right: 0.35rem; }
.secondary { background: #e5e7eb; color: #374151; border: none; padding: 0.4rem 0.75rem; border-radius: 6px; font-size: 0.8125rem; cursor: pointer; }
.secondary:hover { background: #d1d5db; }
</style>
