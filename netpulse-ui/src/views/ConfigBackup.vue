<template>
  <div class="page backup-page">
    <div class="card intro-card">
      <h3>备份说明</h3>
      <p class="intro-text">配置备份用于保存当前系统的<strong>设备列表、告警规则、系统配置</strong>。</p>
      <ul class="backup-what">
        <li><strong>设备</strong>：名称、IP、类型、分组、SSH/SNMP 端口等</li>
        <li><strong>告警规则</strong>：指标、条件、等级、通知渠道等</li>
      </ul>
    </div>

    <div class="card table-loading-wrap">
      <div v-if="listLoading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <div class="toolbar">
        <span class="toolbar-label">备份类型</span>
        <select v-model="backupType" class="backup-type-select">
          <option value="full">全量（设备 + 告警规则 + 系统配置）</option>
          <option value="devices">仅设备</option>
          <option value="alerts">仅告警规则</option>
          <option value="system">仅系统配置</option>
        </select>
        <button class="primary" @click="doBackup">创建备份</button>
        <button class="small" @click="triggerImport">导入备份</button>
        <input ref="importInputRef" type="file" accept=".json,application/json" class="hidden-input" @change="onImportFileChange" />
      </div>
      <div class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>内容摘要</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in list" :key="b.id">
              <td>{{ b.name }}</td>
              <td>{{ backupTypeLabel(b.backupType) }}</td>
              <td class="summary-cell">{{ b.summary || '-' }}</td>
              <td>{{ formatTime(b.createTime) }}</td>
              <td>
                <button class="small" @click="viewContent(b)">查看</button>
                <button class="small" @click="doRestore(b)">还原</button>
                <button class="danger small" @click="doDelete(b.id)">删除</button>
              </td>
            </tr>
            <tr v-if="!list.length"><td colspan="5" class="empty">暂无备份，选择类型后点击「创建备份」</td></tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if="showContent" class="modal-overlay" @click.self="showContent = false">
      <div class="modal card">
        <h3>备份内容（JSON）</h3>
        <pre class="backup-content">{{ viewContentText }}</pre>
        <button @click="showContent = false">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getBackupList, createBackup, deleteBackup, importBackup, restoreBackup } from '../api/backup'
import { formatTime } from '../utils/systemTimezone'

const list = ref([])
const listLoading = ref(false)
const showContent = ref(false)
const viewContentText = ref('')
const backupType = ref('full')
const importInputRef = ref(null)

function backupTypeLabel(t) {
  const m = { full: '全量', devices: '仅设备', alerts: '仅告警规则', system: '仅系统配置' }
  return m[t] || t || '-'
}


function load() {
  listLoading.value = true
  getBackupList()
    .then(r => { list.value = r.data != null ? r.data : [] })
    .catch(() => { list.value = []; alert('备份列表加载失败，请检查网络或后端服务') })
    .finally(() => { listLoading.value = false })
}

function doBackup() {
  const name = 'backup-' + new Date().toISOString().slice(0, 19).replace('T', ' ')
  createBackup({ name, backupType: backupType.value })
    .then(() => { load(); alert('备份已创建') })
    .catch(e => alert(e.response?.data?.message || '创建失败'))
}

function viewContent(b) {
  viewContentText.value = b.content || '（空）'
  showContent.value = true
}

function doDelete(id) {
  if (!confirm('确定删除该备份？')) return
  deleteBackup(id).then(load).catch(e => alert(e.response?.data?.message || '删除失败'))
}

function doRestore(backup) {
  if (!backup?.id) return
  if (!confirm(`确定还原备份「${backup.name}」？该操作会覆盖同键配置。`)) return
  restoreBackup(backup.id)
    .then((r) => {
      const d = r.data || {}
      alert(`还原完成：设备 ${d.restoredDevices ?? 0} 条，告警规则 ${d.restoredAlertRules ?? 0} 条，系统配置 ${d.restoredSystemConfigs ?? 0} 项`)
      load()
    })
    .catch(e => alert(e.response?.data?.message || '还原失败'))
}

function triggerImport() {
  importInputRef.value?.click()
}

function onImportFileChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    const content = typeof reader.result === 'string' ? reader.result : ''
    if (!content.trim()) {
      alert('导入文件为空')
      if (importInputRef.value) importInputRef.value.value = ''
      return
    }
    importBackup({ name: file.name.replace(/\.json$/i, ''), content })
      .then(() => { alert('导入成功'); load() })
      .catch(err => alert(err.response?.data?.message || '导入失败，请检查 JSON 格式'))
      .finally(() => {
        if (importInputRef.value) importInputRef.value.value = ''
      })
  }
  reader.onerror = () => {
    alert('读取文件失败')
    if (importInputRef.value) importInputRef.value.value = ''
  }
  reader.readAsText(file, 'utf-8')
}

onMounted(load)
</script>

<style scoped>
.intro-card { margin-bottom: 1.25rem; background: #f8fafc; border-color: #e2e8f0; }
.intro-card h3 { margin: 0 0 0.5rem 0; font-size: 1rem; }
.intro-text { margin: 0 0 0.5rem 0; font-size: 0.875rem; color: #475569; line-height: 1.5; }
.backup-what { margin: 0; padding-left: 1.25rem; font-size: 0.875rem; color: #475569; line-height: 1.6; }
.backup-what li { margin-bottom: 0.25rem; }
.toolbar { display: flex; align-items: center; gap: 0.75rem 1rem; flex-wrap: wrap; margin-bottom: 1rem; }
.toolbar-label { font-size: 0.875rem; color: #64748b; }
.backup-type-select { min-width: 220px; padding: 0.45rem 0.6rem; border-radius: 8px; border: 1px solid #e2e8f0; }
.summary-cell { font-size: 0.8125rem; color: #64748b; }
.empty { text-align: center; color: #9ca3af; }
.backup-content { max-height: 60vh; overflow: auto; font-size: 0.8rem; white-space: pre-wrap; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { max-width: 720px; width: 90%; max-height: 90vh; overflow: auto; }
.modal h3 { margin: 0 0 1rem; }
button.small { margin-right: 0.35rem; }
.hidden-input { display: none; }
</style>
