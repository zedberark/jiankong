<template>
  <div class="inspection-center page">
    <div class="tabs">
      <button
        type="button"
        :class="['tab', { active: activeTab === 'manual' }]"
        @click="setTab('manual')"
      >手动巡检</button>
      <button
        type="button"
        :class="['tab', { active: activeTab === 'reports' }]"
        @click="setTab('reports')"
      >巡检报告</button>
    </div>

    <!-- 手动巡检 -->
    <section v-show="activeTab === 'manual'" class="panel">
      <div class="toolbar">
        <span class="filter-label">设备分组</span>
        <select v-model="filterGroup" class="filter-select">
          <option value="">全部设备</option>
          <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
        </select>
        <label class="ai-run-label">
          <input v-model="runWithAi" type="checkbox" :disabled="running" />
          AI分析巡检
        </label>
        <button class="primary small" :disabled="running" @click="doRun">{{ running ? '巡检中…' : '开始巡检' }}</button>
      </div>
      <div v-if="lastMessage" class="msg-ok">{{ lastMessage }}</div>
      <div v-if="lastReportPreview" class="last-report-card">
        <h4 class="last-report-title">本次巡检报告</h4>
        <div class="last-report-table-wrap">
          <table class="last-report-table">
            <thead>
              <tr>
                <th>开始时间</th>
                <th>来源</th>
                <th>分组</th>
                <th>设备数</th>
                <th>正常</th>
                <th>延迟高</th>
                <th>离线</th>
                <th>AI</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>{{ formatTime(lastReportPreview.createdAt) }}</td>
                <td><span class="src-tag">{{ sourceLabel(lastReportPreview.source) }}</span></td>
                <td>{{ lastReportPreview.groupName || '全部' }}</td>
                <td>{{ lastReportPreview.totalCount }}</td>
                <td class="ok">{{ lastReportPreview.okCount }}</td>
                <td class="warn">{{ lastReportPreview.warnCount }}</td>
                <td class="off">{{ lastReportPreview.offlineCount }}</td>
                <td>
                  <span v-if="lastReportPreview.aiSummary" class="ai-badge" title="已生成 AI 结论">AI</span>
                  <span v-else class="ai-badge empty">—</span>
                </td>
                <td class="actions">
                  <button type="button" class="link-btn" @click="openDetail(lastReportPreview.id)">明细</button>
                  <button type="button" class="link-btn" @click="goReportsTab">报告列表</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="lastReportPreview.aiSummary" class="ai-inspection-block last-report-ai-block">
          <div class="ai-inspection-head">
            <span class="ai-inspection-title">AI 巡检结论</span>
          </div>
          <div class="ai-summary-text last-report-ai-text">{{ lastReportPreview.aiSummary }}</div>
        </div>
      </div>
    </section>

    <!-- 巡检报告 -->
    <section v-show="activeTab === 'reports'" class="panel reports-panel">
      <div class="toolbar">
        <span class="filter-label">来源</span>
        <select v-model="filterSource" class="filter-select" @change="page = 0; loadReports(false)">
          <option value="">全部</option>
          <option value="MANUAL">手动</option>
          <option value="HOURLY">整点</option>
          <option value="DAILY_00">日报（零点）</option>
          <option value="DAILY_18">日报（18点）</option>
          <option value="WEEKLY_MON">周报（周一）</option>
          <option value="WEEKLY_SUN">周报（周日）</option>
        </select>
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
              <th>开始时间</th>
              <th>来源</th>
              <th>说明</th>
              <th>分组</th>
              <th>设备数</th>
              <th>正常</th>
              <th>延迟高</th>
              <th>离线</th>
              <th>AI</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id">
              <td>{{ formatTime(r.createdAt) }}</td>
              <td><span class="src-tag">{{ sourceLabel(r.source) }}</span></td>
              <td class="sched">{{ r.scheduleLabel || '-' }}</td>
              <td>{{ r.groupName || '全部' }}</td>
              <td>{{ r.totalCount }}</td>
              <td class="ok">{{ r.okCount }}</td>
              <td class="warn">{{ r.warnCount }}</td>
              <td class="off">{{ r.offlineCount }}</td>
              <td><span v-if="r.aiSummary" class="ai-badge" title="已生成 AI 结论">AI</span><span v-else class="ai-badge empty">—</span></td>
              <td class="actions">
                <button type="button" class="link-btn" @click="openDetail(r.id)">明细</button>
                <button type="button" class="link-btn" :disabled="pdfExporting" @click="exportPdfFromList(r.id)">导出PDF</button>
              </td>
            </tr>
            <tr v-if="!list.length"><td colspan="10" class="empty">暂无记录；手动巡检或等待定时任务生成</td></tr>
          </tbody>
        </table>
      </div>
      <div class="pagination">
        <button class="small" :disabled="page <= 0" @click="page--; loadReports(false)">上一页</button>
        <span>第 {{ page + 1 }} 页，共 {{ totalElements }} 条</span>
        <button class="small" :disabled="(page + 1) * size >= totalElements" @click="page++; loadReports(false)">下一页</button>
      </div>
    </section>

    <div v-if="detailVisible" class="modal-mask" @click.self="detailVisible = false">
      <div class="modal-card">
        <div class="modal-head">
          <h3>巡检明细</h3>
          <div class="modal-head-right">
            <button
              v-if="detail && !detailLoading"
              type="button"
              class="link-btn pdf-export-head-btn"
              :disabled="pdfExporting"
              @click="exportPdfModal"
            >{{ pdfExporting ? '导出中…' : '导出 PDF' }}</button>
            <button type="button" class="modal-close" @click="detailVisible = false">×</button>
          </div>
        </div>
        <div v-if="detailLoading" class="modal-loading">加载中…</div>
        <div v-else-if="detail" class="modal-body">
          <div class="ai-inspection-block ai-inspection-block-modal">
            <div class="ai-inspection-head">
              <span class="ai-inspection-title">AI 巡检结论</span>
              <button
                type="button"
                class="small ai-gen-btn"
                :disabled="aiSummaryLoading"
                @click="doGenerateAiSummary"
              >{{ aiSummaryLoading ? '生成中…' : (detail.aiSummary ? '重新生成' : '生成 AI 分析') }}</button>
            </div>
          </div>
          <div ref="modalPdfRef" class="modal-pdf-capture">
            <InspectionReportPdfContent :detail="detail" />
          </div>
        </div>
      </div>
    </div>

    <!-- 列表「导出PDF」：离屏渲染后截图，不在界面闪现 -->
    <div ref="hiddenPdfWrap" class="pdf-offscreen" aria-hidden="true">
      <InspectionReportPdfContent v-if="pdfExportDetail" :detail="pdfExportDetail" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDeviceGroups } from '../api/device'
import { runInspection, getInspectionReports, getInspectionReport, generateInspectionAiSummary } from '../api/inspection'
import { formatTime } from '../utils/systemTimezone'
import { getApiErrorHint } from '../api/request'
import { downloadElementAsPdf } from '../utils/inspectionPdf'
import InspectionReportPdfContent from '../components/InspectionReportPdfContent.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref('manual')

const groupList = ref([])
const filterGroup = ref('')
const runWithAi = ref(true)
const running = ref(false)
const lastMessage = ref('')
/** 最近一次手动巡检返回的报告，用于在手动页下方展示一行摘要 */
const lastReportPreview = ref(null)
const aiSummaryLoading = ref(false)

const filterSource = ref('')
const list = ref([])
const loading = ref(false)
const page = ref(0)
const size = ref(15)
const totalElements = ref(0)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const modalPdfRef = ref(null)
const pdfExportDetail = ref(null)
const hiddenPdfWrap = ref(null)
const pdfExporting = ref(false)

function setTab(tab) {
  activeTab.value = tab
  router.replace({ path: '/inspection', query: tab === 'manual' ? {} : { tab: 'reports' } })
  if (tab === 'reports') loadReports(false)
}

function goReportsTab() {
  setTab('reports')
}

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

function formatRunDone(data) {
  const id = data.id
  const n = data.totalCount
  const ok = data.okCount
  const warn = data.warnCount
  const off = data.offlineCount
  const parts = [`巡检已完成，报告 ID ${id}`]
  if (n != null) parts.push(`共 ${n} 台`)
  if (ok != null || warn != null || off != null) {
    parts.push(`正常 ${ok ?? '-'} / 延迟高 ${warn ?? '-'} / 离线 ${off ?? '-'}`)
  }
  return parts.join('，')
}

function doRun() {
  if (running.value) return
  running.value = true
  lastMessage.value = ''
  lastReportPreview.value = null
  const g = filterGroup.value || ''
  runInspection(g, { ai: runWithAi.value })
    .then(async (r) => {
      const data = r.data
      const id = data && data.id != null ? data.id : ''
      lastMessage.value = id
        ? formatRunDone(data)
        : '巡检已完成'
      if (data && data.id != null) {
        let preview = data
        if (runWithAi.value && !data.aiSummary) {
          try {
            const detailR = await getInspectionReport(data.id)
            const s = detailR.data?.aiSummary
            if (s) preview = { ...data, aiSummary: s }
          } catch (_) { /* 仍以 run 响应为准 */ }
        }
        lastReportPreview.value = preview
        loadReports(true)
      }
    })
    .catch((e) => alert(getApiErrorHint(e, '巡检失败')))
    .finally(() => { running.value = false })
}

/** @param {boolean} [silent] 为 true 时不显示全表加载遮罩（用于手动巡检成功后静默刷新列表） */
function loadReports(silent) {
  if (!silent) loading.value = true
  const params = { page: page.value, size: size.value }
  if (filterSource.value) params.source = filterSource.value
  getInspectionReports(params)
    .then((r) => {
      const data = r.data
      const content = data && data.content != null ? data.content : []
      list.value = Array.isArray(content) ? content : []
      totalElements.value = data && typeof data.totalElements === 'number' ? data.totalElements : list.value.length
    })
    .catch((e) => {
      list.value = []
      totalElements.value = 0
      alert(getApiErrorHint(e, '巡检记录加载失败'))
    })
    .finally(() => {
      if (!silent) loading.value = false
    })
}

function doGenerateAiSummary() {
  if (!detail.value || !detail.value.id || aiSummaryLoading.value) return
  aiSummaryLoading.value = true
  generateInspectionAiSummary(detail.value.id)
    .then((r) => {
      detail.value = r.data
      if (lastReportPreview.value && lastReportPreview.value.id === detail.value.id && r.data) {
        lastReportPreview.value = { ...lastReportPreview.value, aiSummary: r.data.aiSummary }
      }
      loadReports(true)
    })
    .catch((e) => alert(getApiErrorHint(e, 'AI 分析生成失败')))
    .finally(() => { aiSummaryLoading.value = false })
}

async function exportPdfModal() {
  if (!detail.value || !modalPdfRef.value || pdfExporting.value) return
  pdfExporting.value = true
  try {
    await downloadElementAsPdf(modalPdfRef.value, `巡检报告-${detail.value.id}.pdf`)
  } catch (e) {
    alert(e?.message || '导出 PDF 失败')
  } finally {
    pdfExporting.value = false
  }
}

async function exportPdfFromList(id) {
  if (pdfExporting.value) return
  pdfExporting.value = true
  try {
    const r = await getInspectionReport(id)
    pdfExportDetail.value = r.data
    await nextTick()
    await nextTick()
    const target = hiddenPdfWrap.value?.querySelector('.inspection-pdf-content')
    if (!target) throw new Error('导出区域未就绪')
    await downloadElementAsPdf(target, `巡检报告-${id}.pdf`)
  } catch (e) {
    // axios 失败用统一提示；html2canvas/jsPDF 等非 HTTP 错误只展示真实原因，避免误判「后端不可达」
    if (e && e.isAxiosError) {
      alert(getApiErrorHint(e, '导出失败'))
    } else {
      alert(e?.message || '导出 PDF 失败')
    }
  } finally {
    pdfExportDetail.value = null
    pdfExporting.value = false
  }
}

function openDetail(id) {
  detailVisible.value = true
  detail.value = null
  detailLoading.value = true
  getInspectionReport(id)
    .then((r) => { detail.value = r.data })
    .catch((e) => {
      detail.value = null
      alert(getApiErrorHint(e, '加载明细失败'))
    })
    .finally(() => { detailLoading.value = false })
}

onMounted(() => {
  const q = route.query.tab
  if (q === 'reports') {
    activeTab.value = 'reports'
    loadReports(false)
  }
  getDeviceGroups().then((r) => {
    groupList.value = Array.isArray(r.data) ? r.data : []
  }).catch(() => { groupList.value = [] })
})

watch(() => route.query.tab, (t) => {
  if (t === 'reports') {
    activeTab.value = 'reports'
    loadReports(false)
  } else if (t === undefined || t === '') {
    activeTab.value = 'manual'
  }
})
</script>

<style scoped>
.inspection-center.page { max-width: 1200px; }
.tabs {
  display: flex; gap: 0.25rem; margin-bottom: 1.25rem;
  border-bottom: 1px solid #e2e8f0; padding-bottom: 0;
}
.tab {
  padding: 0.5rem 1.1rem; font-size: 0.9rem; font-weight: 500;
  border: none; border-bottom: 2px solid transparent; background: none; color: #64748b;
  cursor: pointer; margin-bottom: -1px;
}
.tab:hover { color: #0f172a; }
.tab.active { color: #0284c7; border-bottom-color: #0284c7; }

.panel { margin-bottom: 0.5rem; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
.filter-label { font-size: 0.875rem; color: #64748b; }
.filter-select { padding: 0.4rem 0.75rem; border: 1px solid #e5e7eb; border-radius: 6px; min-width: 160px; }
.ai-run-label { font-size: 0.8125rem; color: #475569; display: inline-flex; align-items: center; gap: 0.35rem; cursor: pointer; user-select: none; }
.ai-run-label input { cursor: pointer; }
.ai-badge {
  display: inline-block; font-size: 0.65rem; font-weight: 700; padding: 0.12rem 0.4rem; border-radius: 4px;
  background: linear-gradient(135deg, #7c3aed 0%, #5b21b6 100%); color: #fff;
}
.ai-badge.empty { background: #f1f5f9; color: #94a3b8; font-weight: 500; }
.ai-inspection-block {
  margin-bottom: 1rem; padding: 0.85rem 1rem; background: #faf5ff; border: 1px solid #e9d5ff; border-radius: 8px;
}
.ai-inspection-head { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.5rem; }
.ai-inspection-title { font-weight: 600; color: #5b21b6; font-size: 0.9rem; }
.ai-gen-btn {
  padding: 0.3rem 0.65rem; border-radius: 6px; border: 1px solid #a78bfa; background: #fff;
  color: #5b21b6; font-size: 0.75rem; cursor: pointer;
}
.ai-gen-btn:hover:not(:disabled) { background: #f5f3ff; }
.ai-gen-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.ai-summary-text {
  white-space: pre-wrap; word-break: break-word; font-size: 0.8125rem; line-height: 1.55; color: #334155;
  max-height: 220px; overflow: auto; padding: 0.5rem 0.65rem; background: #fff; border-radius: 6px; border: 1px solid #ede9fe;
}
button.primary.small {
  background: linear-gradient(180deg, var(--color-primary) 0%, var(--color-primary-hover) 100%);
  border-color: var(--color-primary);
  color: #fff;
  box-shadow: var(--shadow-sm);
}
button.primary.small:hover:not(:disabled) {
  background: linear-gradient(180deg, var(--color-primary-hover) 0%, #0369a1 100%);
  border-color: var(--color-primary-hover);
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.35);
}
.msg-ok { font-size: 0.875rem; color: #15803d; background: #f0fdf4; border: 1px solid #bbf7d0; padding: 0.75rem 1rem; border-radius: 8px; }

.last-report-card {
  margin-top: 1rem; padding: 1rem 1.1rem; background: #fff; border: 1px solid #bae6fd; border-radius: 10px;
  box-shadow: 0 1px 3px rgba(14, 165, 233, 0.12);
}
.last-report-title { margin: 0 0 0.75rem; font-size: 1rem; color: #0c4a6e; }
.last-report-ai-block { margin-top: 1rem; }
.last-report-ai-text { max-height: min(48vh, 520px); }
.last-report-table-wrap { overflow: auto; border: 1px solid #e2e8f0; border-radius: 8px; }
.last-report-table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; min-width: 720px; }
.last-report-table th, .last-report-table td { padding: 0.5rem 0.65rem; text-align: left; border-bottom: 1px solid #f1f5f9; }
.last-report-table th { background: #f8fafc; font-weight: 600; color: #475569; white-space: nowrap; }
.last-report-table tbody tr:last-child td { border-bottom: none; }

.src-tag { font-size: 0.75rem; background: #e0e7ff; color: #3730a3; padding: 0.15rem 0.45rem; border-radius: 4px; }
.sched { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ok { color: #15803d; font-weight: 600; }
.warn { color: #b45309; font-weight: 600; }
.off { color: #b91c1c; font-weight: 600; }
.actions { white-space: nowrap; }
.link-btn { background: none; border: none; color: #2563eb; cursor: pointer; font-size: 0.8125rem; padding: 0 0.35rem; }
.empty { text-align: center; color: #9ca3af; }
.pagination { margin-top: 1rem; display: flex; align-items: center; gap: 1rem; }
/* 仅分页按钮用白底，勿用全局 .small，否则会盖住 toolbar 里「开始巡检」的 button.primary 渐变 */
.pagination .small { padding: 0.35rem 0.65rem; border-radius: 6px; border: 1px solid #e5e7eb; background: #fff; cursor: pointer; font-size: 0.8125rem; }

.modal-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.45); z-index: 1000; display: flex; align-items: center; justify-content: center; padding: 1rem; }
.modal-card { background: #fff; border-radius: 12px; max-width: 900px; width: 100%; max-height: 85vh; display: flex; flex-direction: column; box-shadow: 0 20px 50px rgba(0,0,0,0.15); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.25rem; border-bottom: 1px solid #e5e7eb; gap: 0.75rem; }
.modal-head h3 { margin: 0; font-size: 1.05rem; flex: 1; }
.modal-head-right { display: flex; align-items: center; gap: 0.35rem; flex-shrink: 0; }
.pdf-export-head-btn { font-size: 0.875rem; }
.modal-close { border: none; background: none; font-size: 1.5rem; line-height: 1; cursor: pointer; color: #64748b; }
.pdf-offscreen {
  position: fixed;
  left: -99999px;
  top: 0;
  width: 800px;
  z-index: -1;
  background: #fff;
  padding: 12px;
  pointer-events: none;
}
.modal-pdf-capture { margin-top: 0; }
.ai-inspection-block-modal { margin-bottom: 0.75rem; }
.modal-body { padding: 0 1.25rem 1.25rem; overflow: auto; }
.modal-loading { padding: 2rem; text-align: center; color: #64748b; }
</style>
