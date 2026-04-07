<template>
  <div class="inspection-pdf-content">
    <p class="pdf-meta">
      {{ formatTime(detail.createdAt) }}
      · {{ sourceLabel(detail.source) }}
      · {{ detail.scheduleLabel || '-' }}
      · 分组 {{ detail.groupName || '全部' }}
      · 设备 {{ detail.totalCount }} 台 · 正常 {{ detail.okCount }} · 延迟高 {{ detail.warnCount }} · 离线 {{ detail.offlineCount }}
    </p>
    <div v-if="detail.aiSummary" class="pdf-ai-block">
      <h4 class="pdf-ai-title">AI 巡检结论</h4>
      <div class="pdf-ai-text">{{ detail.aiSummary }}</div>
    </div>
    <div class="pdf-table-wrap">
      <table class="pdf-table">
        <thead>
          <tr>
            <th>设备名</th>
            <th>IP</th>
            <th>类型</th>
            <th>RTT(ms)</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="it in (detail.items || [])" :key="it.id">
            <td>{{ it.deviceName || '-' }}</td>
            <td>{{ it.ip || '-' }}</td>
            <td>{{ it.deviceType || '-' }}</td>
            <td>{{ it.rttMs != null ? it.rttMs : '-' }}</td>
            <td>{{ statusLabel(it.status) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { formatTime } from '../utils/systemTimezone'

defineProps({
  detail: { type: Object, required: true },
})

function sourceLabel(s) {
  const m = {
    MANUAL: '手动',
    HOURLY: '整点',
    DAILY_00: '日报零点',
    DAILY_18: '日报18点',
    WEEKLY_MON: '周报周一',
    WEEKLY_SUN: '周报周日',
  }
  return m[s] || s || '-'
}

function statusLabel(s) {
  if (s === 'normal') return '正常'
  if (s === 'warning') return '延迟偏高'
  if (s === 'offline') return '离线'
  return s || '-'
}
</script>

<style scoped>
.inspection-pdf-content {
  font-family: system-ui, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  color: #0f172a;
  font-size: 12px;
  line-height: 1.45;
  background: #fff;
  padding: 0.5rem 0;
}
.pdf-meta {
  margin: 0 0 0.75rem;
  font-size: 11px;
  color: #475569;
}
.pdf-ai-block {
  margin-bottom: 0.75rem;
  padding: 0.65rem 0.75rem;
  background: #faf5ff;
  border: 1px solid #e9d5ff;
  border-radius: 6px;
}
.pdf-ai-title {
  margin: 0 0 0.35rem;
  font-size: 12px;
  color: #5b21b6;
}
.pdf-ai-text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
  color: #334155;
}
.pdf-table-wrap {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}
.pdf-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}
.pdf-table th,
.pdf-table td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid #f1f5f9;
}
.pdf-table th {
  background: #f8fafc;
  font-weight: 600;
  color: #475569;
}
.pdf-table tbody tr:last-child td {
  border-bottom: none;
}
</style>
