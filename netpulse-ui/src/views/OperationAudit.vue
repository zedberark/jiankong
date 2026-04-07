<template>
  <div class="page">
    <div class="toolbar">
      <input v-model="filterUsername" placeholder="操作人" class="filter-input" />
      <input v-model="filterAction" placeholder="操作类型" class="filter-input" />
      <button class="primary small" @click="load" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button>
      <button class="secondary small" @click="doExport">导出 CSV</button>
    </div>
    <div class="card table-wrapper table-loading-wrap">
      <div v-if="loading" class="table-loading-overlay">
        <div>
          <div class="loading-spinner" aria-hidden="true"></div>
          <p class="loading-text">加载中…</p>
        </div>
      </div>
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>操作人</th>
            <th>操作类型</th>
            <th>对象类型</th>
            <th>对象ID</th>
            <th>详情</th>
            <th>IP</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in list" :key="a.id">
            <td>{{ formatTime(a.createTime) }}</td>
            <td>{{ a.username || '匿名' }}</td>
            <td>{{ a.action }}</td>
            <td>{{ a.targetType || '-' }}</td>
            <td>{{ a.targetId != null ? a.targetId : '-' }}</td>
            <td class="detail-cell">{{ a.detail || '-' }}</td>
            <td>{{ a.ip || '-' }}</td>
          </tr>
          <tr v-if="!list.length"><td colspan="7" class="empty">暂无审计记录</td></tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <button class="small" :disabled="page <= 0" @click="page--; load()">上一页</button>
      <span>第 {{ page + 1 }} 页，共 {{ totalElements }} 条</span>
      <button class="small" :disabled="(page + 1) * size >= totalElements" @click="page++; load()">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAuditLogs } from '../api/audit'
import { exportAuditLog } from '../api/export'
import { formatTime } from '../utils/systemTimezone'

const list = ref([])
const loading = ref(false)
const filterUsername = ref('')
const filterAction = ref('')
const page = ref(0)
const size = ref(20)
const totalElements = ref(0)

function load() {
  loading.value = true
  getAuditLogs({
    page: page.value,
    size: size.value,
    username: filterUsername.value || undefined,
    action: filterAction.value || undefined,
  }).then(r => {
    const data = r.data
    list.value = (data && data.content) ? data.content : (Array.isArray(data) ? data : [])
    totalElements.value = (data && typeof data.totalElements === 'number') ? data.totalElements : list.value.length
  }).catch(() => {
    list.value = []
    totalElements.value = 0
    alert('审计日志加载失败，请检查网络或后端服务')
  }).finally(() => { loading.value = false })
}

function doExport() {
  exportAuditLog(filterUsername.value || undefined)
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
.filter-input { padding: 0.4rem 0.75rem; border: 1px solid #e5e7eb; border-radius: 6px; width: 140px; }
.detail-cell { max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { text-align: center; color: #9ca3af; }
.pagination { margin-top: 1rem; display: flex; align-items: center; gap: 1rem; }
.secondary { background: #e5e7eb; color: #374151; border: none; padding: 0.4rem 0.75rem; border-radius: 6px; font-size: 0.8125rem; cursor: pointer; }
</style>
