<template>
  <div class="login-page">
    <div class="card">
      <div class="left" aria-hidden="true">
        <div class="left-bg" />
        <div class="float-wrap">
          <div class="orb orb-a" />
          <div class="orb orb-b" />
          <div class="ring" />
        </div>
        <div class="left-content">
          <div class="brand-mark">
            <span class="brand-icon" aria-hidden="true">
              <span class="brand-icon-inner" />
            </span>
          </div>
          <h2 class="brand-title">监控运维系统</h2>
          <p class="brand-desc">网络监控与运维平台</p>
        </div>
      </div>

      <section class="right" aria-label="用户登录">
        <h1 class="form-title">欢迎登录</h1>
        <p class="form-hint">请输入账号信息</p>
        <form @submit.prevent="submit" class="login-form">
          <div class="form-group">
            <label for="login-user">用户名</label>
            <input
              id="login-user"
              v-model="username"
              type="text"
              placeholder="请输入用户名"
              required
              autocomplete="username"
            />
          </div>
          <div class="form-group">
            <label for="login-pass">密码</label>
            <input
              id="login-pass"
              v-model="password"
              type="password"
              placeholder="请输入密码"
              required
              autocomplete="current-password"
            />
          </div>
          <p v-if="error" class="error">{{ error }}</p>
          <button type="submit" class="btn-login" :disabled="loading">
            <span v-if="loading" class="btn-spin" aria-hidden="true" />
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 登录页：单卡片左右分栏；左侧品牌与动效，右侧表单。提交后写入 localStorage 并跳转（含 from 回跳）。
 */
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login } from '../api/auth'
import { getApiErrorHint } from '../api/request'
import { STORAGE_KEYS } from '../utils/constants'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const STORAGE_KEY = STORAGE_KEYS.USER

function submit() {
  error.value = ''
  loading.value = true
  login(username.value, password.value)
    .then(r => {
      const data = r.data
      if (data && data.success && data.user) {
        try {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(data.user))
        } catch (_) { /* 忽略存储异常 */ }
        const from = route.query.from
        router.replace(typeof from === 'string' && from && from !== '/login' ? from : '/dashboard')
      } else {
        error.value = (typeof data?.message === 'string' && data.message) ? data.message : '登录失败：服务器未返回成功状态'
      }
    })
    .catch(e => {
      const st = e.response?.status
      if (st === 401) {
        const m = e.response?.data?.message
        error.value = typeof m === 'string' && m ? m : '用户名或密码错误'
        return
      }
      if (!e.response) {
        error.value =
          '无法连接后端：请确认 ① 后端已启动（默认端口 8083，与 application.yml 一致）② 前端用 npm run dev 打开（Vite 会把 /api 代理到 localhost:8083）③ 不要用旧书签访问仍指向 8082 的页面'
        return
      }
      error.value = getApiErrorHint(e, '登录失败')
    })
    .finally(() => { loading.value = false })
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(ellipse 80% 50% at 20% 10%, rgba(14, 165, 233, 0.12), transparent 55%),
    radial-gradient(ellipse 60% 40% at 90% 80%, rgba(59, 130, 246, 0.1), transparent 50%),
    linear-gradient(165deg, #f0f9ff 0%, #f8fafc 45%, #eef2ff 100%);
}

.card {
  width: 100%;
  max-width: 920px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    0 24px 48px rgba(15, 23, 42, 0.08),
    0 0 0 1px rgba(148, 163, 184, 0.12);
}

.left {
  position: relative;
  min-height: 420px;
  padding: 40px 36px;
  border-right: 1px solid #e8eef8;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.left-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(145deg, #0c4a6e 0%, #0369a1 42%, #0ea5e9 100%);
}

.float-wrap {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(0.5px);
  animation: float 12s ease-in-out infinite;
}

.orb-a {
  width: 160px;
  height: 160px;
  left: -20px;
  top: 15%;
  background: radial-gradient(circle at 35% 35%, rgba(255, 255, 255, 0.22), transparent 65%);
  animation-delay: 0s;
}

.orb-b {
  width: 220px;
  height: 220px;
  right: -40px;
  bottom: 5%;
  background: radial-gradient(circle at 50% 50%, rgba(125, 211, 252, 0.2), transparent 68%);
  animation-delay: -4s;
  animation-duration: 14s;
}

.ring {
  position: absolute;
  left: 50%;
  top: 48%;
  width: 280px;
  height: 280px;
  margin-left: -140px;
  margin-top: -140px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.14);
  animation: spin 36s linear infinite;
}

.ring::after {
  content: '';
  position: absolute;
  inset: 22%;
  border-radius: 50%;
  border: 1px dashed rgba(255, 255, 255, 0.1);
  animation: spin 24s linear infinite reverse;
}

.left-content {
  position: relative;
  z-index: 1;
  text-align: center;
  color: #fff;
}

.brand-mark {
  margin-bottom: 16px;
}

.brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid rgba(255, 255, 255, 0.25);
  animation: float 10s ease-in-out infinite;
  animation-delay: -2s;
}

.brand-icon-inner {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #fff 0%, rgba(255, 255, 255, 0.65) 100%);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
}

.brand-title {
  margin: 0 0 8px;
  font-size: 1.5rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

.brand-desc {
  margin: 0;
  font-size: 0.9375rem;
  opacity: 0.88;
  line-height: 1.5;
}

.right {
  padding: 40px 40px 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: #fff;
}

.form-title {
  margin: 0 0 6px;
  font-size: 1.375rem;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.02em;
}

.form-hint {
  margin: 0 0 26px;
  font-size: 0.875rem;
  color: #64748b;
}

.login-form {
  margin: 0;
}

.form-group {
  margin-bottom: 1.1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.4rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #334155;
}

.form-group input {
  width: 100%;
  padding: 0.65rem 0.9rem;
  font-size: 0.9375rem;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-sizing: border-box;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.form-group input:focus {
  outline: none;
  border-color: #0ea5e9;
  box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.15);
}

.error {
  margin: 0 0 0.75rem;
  font-size: 0.875rem;
  color: #dc2626;
  padding: 0.5rem 0.75rem;
  background: #fef2f2;
  border-radius: 8px;
}

.btn-login {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0.72rem 1rem;
  font-size: 1rem;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(180deg, #0ea5e9 0%, #0284c7 100%);
  border: none;
  border-radius: 10px;
  cursor: pointer;
  margin-top: 0.25rem;
  transition: box-shadow 0.2s;
}

.btn-login:hover:not(:disabled) {
  box-shadow: 0 4px 14px rgba(14, 165, 233, 0.4);
}

.btn-login:disabled {
  opacity: 0.75;
  cursor: not-allowed;
}

.btn-spin {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}

@keyframes float {
  0%,
  100% {
    transform: translateY(0px) translateX(0px);
  }
  50% {
    transform: translateY(-14px) translateX(8px);
  }
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 920px) {
  .card {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .left {
    border-right: 0;
    border-bottom: 1px solid #e8eef8;
    padding-right: 0;
    padding-bottom: 32px;
    min-height: 280px;
  }

  .right {
    padding-left: 0;
    padding-top: 32px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb,
  .ring,
  .ring::after,
  .brand-icon,
  .btn-spin {
    animation: none;
  }
}
</style>
