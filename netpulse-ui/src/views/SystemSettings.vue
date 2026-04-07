<template>
  <div class="page system-settings-page">
    <div class="settings-grid">
      <!-- 基础设置 -->
      <div class="card settings-card">
        <div class="card-header">
          <h2 class="card-title">基础设置</h2>
        </div>
        <div class="card-body">
          <div class="form-row">
            <div class="form-group">
              <label>系统名称</label>
              <input v-model="config.system_name" placeholder="监控运维系统" />
            </div>
            <div class="form-group">
              <label>系统版本</label>
              <input v-model="config.system_version" placeholder="1.0.0" />
            </div>
          </div>
          <div class="form-group">
            <label>NTP / 时区</label>
            <select v-model="config.system_timezone">
              <option v-for="opt in timezoneOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
        </div>
      </div>

      <!-- API 设置 -->
      <div class="card settings-card">
        <div class="card-header">
          <h2 class="card-title">API 设置</h2>
          <p class="card-hint">千问、DeepSeek 密钥仅展示脱敏，保存时填写完整密钥才会更新。</p>
          <div class="api-check-actions">
            <button class="secondary btn-check" type="button" @click="checkApiHealth" :disabled="apiChecking">
              <span v-if="apiChecking" class="btn-loading-dot" aria-hidden="true"></span>
              {{ apiChecking ? '检测中…' : '一键检测 API 连通性' }}
            </button>
          </div>
        </div>
        <div class="card-body api-cards">
          <section class="api-block">
            <h3 class="api-block-title">千问 API</h3>
            <div class="form-group">
              <label class="checkbox-label">
                <input type="checkbox" v-model="qwen.enabled" true-value="1" false-value="0" />
                启用
              </label>
            </div>
            <div class="form-group">
              <label>端点</label>
              <input v-model="qwen.endpoint" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" />
            </div>
            <div class="form-group">
              <label>API Key</label>
              <input v-model="qwen.key" type="password" placeholder="留空则不修改已保存的密钥" />
              <span v-if="qwen.keyMasked" class="key-hint">已保存：{{ qwen.keyMasked }}</span>
              <span v-if="apiHealth.qwen" class="api-health" :class="'health-' + (apiHealth.qwen.ok ? 'ok' : 'err')">
                {{ apiHealthText(apiHealth.qwen) }}
              </span>
            </div>
          </section>
          <section class="api-block">
            <h3 class="api-block-title">DeepSeek API</h3>
            <div class="form-group">
              <label class="checkbox-label">
                <input type="checkbox" v-model="deepseek.enabled" true-value="1" false-value="0" />
                启用
              </label>
            </div>
            <div class="form-group">
              <label>端点</label>
              <input v-model="deepseek.endpoint" placeholder="https://api.deepseek.com/v1/chat/completions" />
            </div>
            <div class="form-group">
              <label>API Key</label>
              <input v-model="deepseek.key" type="password" placeholder="留空则不修改已保存的密钥" />
              <span v-if="deepseek.keyMasked" class="key-hint">已保存：{{ deepseek.keyMasked }}</span>
              <span v-if="apiHealth.deepseek" class="api-health" :class="'health-' + (apiHealth.deepseek.ok ? 'ok' : 'err')">
                {{ apiHealthText(apiHealth.deepseek) }}
              </span>
            </div>
          </section>
        </div>
      </div>

      <!-- 告警邮件通知 -->
      <div class="card settings-card email-card">
        <div class="card-header">
          <h2 class="card-title">告警邮件通知</h2>
          <p class="card-hint">在「告警通知」中添加/编辑规则时可勾选「邮件通知」，该规则触发时会发邮件。</p>
        </div>
        <div class="card-body">
          <div class="form-group">
            <label class="checkbox-label">
              <input type="checkbox" v-model="notifySettings.emailEnabled" true-value="1" false-value="0" />
              启用告警邮件通知
            </label>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>SMTP 主机</label>
              <input v-model="notifySettings.smtpHost" type="text" placeholder="smtp.163.com" />
            </div>
            <div class="form-group">
              <label>SMTP 端口</label>
              <input v-model="notifySettings.smtpPort" type="text" placeholder="465" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>发件人邮箱</label>
              <input v-model="notifySettings.fromEmail" type="text" placeholder="您的163邮箱地址" />
            </div>
            <div class="form-group">
              <label>SMTP 用户名</label>
              <input v-model="notifySettings.smtpUser" type="text" placeholder="yourname@163.com" />
            </div>
          </div>
          <div class="form-group">
            <label>SMTP 授权码</label>
            <input v-model="notifySettings.smtpPassword" type="password" placeholder="留空则不修改已保存的授权码" />
          </div>
        </div>
      </div>
    </div>

    <div class="save-bar">
      <button class="primary btn-save" @click="save">保存全部设置</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getSystemConfig, saveSystemConfig, getApiSettings, saveApiSettings, checkApiSettingsHealth } from '../api/system'
import { getAlertNotifySettings, saveAlertNotifySettings } from '../api/alerts'
import { loadSystemTimezone } from '../utils/systemTimezone'

const config = ref({ system_name: '', system_version: '', system_timezone: 'Asia/Shanghai' })
const qwen = ref({ enabled: '0', endpoint: '', key: '', keyMasked: '' })
const deepseek = ref({ enabled: '0', endpoint: '', key: '', keyMasked: '' })
const apiChecking = ref(false)
const apiHealth = ref({ qwen: null, deepseek: null })
const notifySettings = ref({
  emailEnabled: '0',
  smtpHost: 'smtp.163.com',
  smtpPort: '465',
  smtpUser: '',
  smtpPassword: '',
  fromEmail: '',
})

const timezoneOptions = [
  // 中国默认只保留上海，其他地区合并为该选项，避免重复
  { value: 'Asia/Shanghai', label: '中国上海 (UTC+8)' },
  // 其他地区每个保留一个代表城市
  { value: 'Asia/Tokyo', label: '日本东京 (UTC+9)' },
  { value: 'Asia/Singapore', label: '新加坡 (UTC+8)' },
  { value: 'Europe/London', label: '英国伦敦 (UTC+0/1)' },
  { value: 'America/New_York', label: '美国纽约 (UTC-5/-4)' },
  { value: 'America/Los_Angeles', label: '美国洛杉矶 (UTC-8/-7)' },
  { value: 'UTC', label: 'UTC' },
]

function load() {
  getSystemConfig()
    .then(r => {
      const list = r.data != null ? r.data : []
      list.forEach(c => {
        if (c.configKey === 'system.name') config.value.system_name = c.configValue || ''
        if (c.configKey === 'system.version') config.value.system_version = c.configValue || ''
        if (c.configKey === 'system.timezone') config.value.system_timezone = c.configValue || 'Asia/Shanghai'
      })
    })
    .catch(() => { alert('系统配置加载失败，请检查网络或后端服务') })
  getApiSettings()
    .then(r => {
      if (r.data && r.data.qwen) {
        const q = r.data.qwen
        qwen.value.enabled = q.enabled ?? '0'
        qwen.value.endpoint = q.endpoint ?? ''
        qwen.value.key = ''
        qwen.value.keyMasked = (q.key && q.key !== '') ? q.key : ''
      }
      if (r.data && r.data.deepseek) {
        const d = r.data.deepseek
        deepseek.value.enabled = d.enabled ?? '0'
        deepseek.value.endpoint = d.endpoint ?? ''
        deepseek.value.key = ''
        deepseek.value.keyMasked = (d.key && d.key !== '') ? d.key : ''
      }
    })
    .catch(() => { alert('API 设置加载失败，请检查网络或后端服务') })
  getAlertNotifySettings()
    .then(r => {
      const d = r.data
      if (d) {
        notifySettings.value = {
          emailEnabled: d.emailEnabled ?? '0',
          smtpHost: d.smtpHost ?? 'smtp.163.com',
          smtpPort: d.smtpPort ?? '465',
          smtpUser: d.smtpUser ?? '',
          smtpPassword: d.smtpPassword ?? '',
          fromEmail: d.fromEmail ?? '',
        }
      }
    })
    .catch(() => {})
}

function apiHealthText(item) {
  if (!item) return ''
  if (item.ok) {
    const latency = item.latencyMs != null ? `，延迟 ${item.latencyMs}ms` : ''
    return `可用${latency}`
  }
  return item.message || '不可用'
}

function checkApiHealth() {
  apiChecking.value = true
  checkApiSettingsHealth()
    .then((r) => {
      const data = r.data || {}
      apiHealth.value = {
        qwen: data.qwen || null,
        deepseek: data.deepseek || null,
      }
    })
    .catch((e) => {
      const msg = e.response?.data?.message || e.message || 'API 健康检查失败'
      if (String(msg).includes('No static resource system/api-settings/health-check')) {
        alert('当前后端还是旧版本，缺少“API 健康检查”接口。请重启后端服务后重试。')
        return
      }
      alert(msg)
    })
    .finally(() => { apiChecking.value = false })
}

function save() {
  const promises = [
    saveSystemConfig({ configKey: 'system.name', configValue: config.value.system_name || '监控运维系统' }),
    saveSystemConfig({ configKey: 'system.version', configValue: config.value.system_version || '1.0.0' }),
    saveSystemConfig({ configKey: 'system.timezone', configValue: config.value.system_timezone || 'Asia/Shanghai' }),
    saveApiSettings({
      qwen: { enabled: qwen.value.enabled, endpoint: qwen.value.endpoint, key: qwen.value.key || undefined },
      deepseek: { enabled: deepseek.value.enabled, endpoint: deepseek.value.endpoint, key: deepseek.value.key || undefined },
    }),
    saveAlertNotifySettings(notifySettings.value),
  ]
  Promise.all(promises).then(() => {
    loadSystemTimezone()
    alert('已保存')
  }).catch(e => alert(e.response?.data?.message || '保存失败'))
}

onMounted(load)
</script>

<style scoped>
.system-settings-page { }
.settings-grid { display: grid; grid-template-columns: 1fr; gap: 1.25rem; max-width: 960px; }
@media (min-width: 900px) {
  .settings-grid { grid-template-columns: 1fr 1fr; }
  .email-card { grid-column: 1 / -1; }
}
.settings-card { display: flex; flex-direction: column; overflow: hidden; }
.card-header { padding: 1rem 1.25rem 0; }
.card-title { margin: 0 0 0.35rem; font-size: 1.0625rem; font-weight: 600; color: #111827; }
.card-hint { margin: 0; font-size: 0.8125rem; color: #6b7280; line-height: 1.5; }
.card-hint code { background: #f3f4f6; padding: 0.12rem 0.35rem; border-radius: 4px; font-size: 0.75rem; }
.api-check-actions { margin-top: 0.6rem; }
.btn-check { font-size: 0.8125rem; padding: 0.4rem 0.75rem; border-radius: 8px; }
.card-body { padding: 1rem 1.25rem 1.25rem; }
.form-group { margin-bottom: 1rem; }
.form-group:last-child { margin-bottom: 0; }
.form-group label { display: block; margin-bottom: 0.4rem; font-size: 0.8125rem; font-weight: 500; color: #374151; }
.form-group input,
.form-group select { width: 100%; max-width: 100%; padding: 0.5rem 0.75rem; font-size: 0.875rem; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; box-sizing: border-box; }
.form-group input:focus,
.form-group select:focus { outline: none; border-color: #3b82f6; box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.15); }
.form-group input::placeholder { color: #9ca3af; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
.form-row .form-group { margin-bottom: 1rem; }
.form-row .form-group:last-child { margin-bottom: 0; }
.checkbox-label { display: inline-flex; align-items: center; gap: 0.5rem; cursor: pointer; font-weight: 400; }
.form-group input[type="checkbox"] { width: 1rem; height: 1rem; margin: 0; accent-color: #3b82f6; }
.key-hint { display: block; margin-top: 0.3rem; font-size: 0.75rem; color: #9ca3af; }
.api-health { display: block; margin-top: 0.35rem; font-size: 0.75rem; }
.api-health.health-ok { color: #047857; }
.api-health.health-err { color: #b91c1c; }
.btn-loading-dot {
  width: 12px;
  height: 12px;
  border: 2px solid #cbd5e1;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-right: 0.25rem;
  display: inline-block;
  vertical-align: -1px;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.api-cards { display: grid; grid-template-columns: 1fr; gap: 1.25rem; }
@media (min-width: 520px) { .api-cards { grid-template-columns: 1fr 1fr; } }
.api-block { padding: 1rem; background: #f9fafb; border-radius: 10px; border: 1px solid #f3f4f6; }
.api-block-title { margin: 0 0 0.75rem; font-size: 0.9375rem; font-weight: 600; color: #4b5563; }
.save-bar { margin-top: 1.5rem; padding: 1rem 0; border-top: 1px solid #e5e7eb; display: flex; justify-content: flex-start; max-width: 960px; }
.btn-save { padding: 0.6rem 1.5rem; font-size: 0.9375rem; border-radius: 8px; }
</style>
