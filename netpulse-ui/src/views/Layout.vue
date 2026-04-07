<template>
  <div class="layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <aside class="sidebar">
      <div class="sidebar-top">
        <div class="logo">
          <span class="logo-icon">◆</span>
          <span class="logo-text">监控运维系统</span>
        </div>
        <button type="button" class="btn-collapse" :title="sidebarCollapsed ? '展开菜单' : '折叠菜单'" aria-label="折叠侧栏" @click="toggleSidebar">
          <svg class="collapse-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path v-if="!sidebarCollapsed" d="M15 18l-6-6 6-6"/>
            <path v-else d="M9 18l6-6-6-6"/>
          </svg>
        </button>
      </div>
      <nav class="nav">
        <template v-for="(item, idx) in menuList" :key="item.code || item.group || 'nav-' + idx">
          <span v-if="item.group" class="nav-group">{{ item.group }}</span>
          <router-link v-else-if="item.path && canShowMenu(item)" :to="item.path" active-class="active">{{ item.title }}</router-link>
        </template>
      </nav>
    </aside>
    <main class="main">
      <header class="header">
        <button v-if="sidebarCollapsed" type="button" class="btn-expand" title="展开菜单" aria-label="展开侧栏" @click="toggleSidebar">
          <svg class="expand-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="4" y1="6" x2="20" y2="6"/>
            <line x1="4" y1="12" x2="20" y2="12"/>
            <line x1="4" y1="18" x2="20" y2="18"/>
          </svg>
          <span class="expand-text">菜单</span>
        </button>
        <h1 class="page-title">{{ $route.meta.title || '监控运维系统' }}</h1>
        <div class="header-right">
          <span class="greeting">{{ greetingText }}，</span>
          <span class="current-user">{{ currentUserName }}</span>
          <button type="button" class="btn-logout" @click="logout">退出</button>
        </div>
      </header>
      <div class="content">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </main>
  </div>
</template>

<script setup>
/**
 * 主布局：侧边栏菜单完全按登录返回的 allowedMenus（角色权限模板）控制可见，顶栏标题与当前用户、主内容区路由出口。
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loadSystemTimezone, systemTimezone } from '../utils/systemTimezone'
import { STORAGE_KEYS } from '../utils/constants'
import { userHasSidebarMenuAccess } from '../utils/menuAccess'

const SIDEBAR_COLLAPSED_KEY = 'netpulse_sidebar_collapsed'

const router = useRouter()
const route = useRoute()
const currentUserName = ref('')
/** 当前登录用户对象（含 roles、allowedMenus），供菜单权限判断 */
const currentUser = ref(null)
const sidebarCollapsed = ref(false)

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  try {
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0')
  } catch (_) {}
}

/** 菜单配置：分组标题 + 路由与 code，code 用于权限过滤 */
const menuList = [
  { group: '概览' },
  { code: 'dashboard', path: '/dashboard', title: '首页仪表盘' },
  { group: '设备管理' },
  { code: 'devices', path: '/devices', title: '设备管理' },
  { code: 'metrics', path: '/metrics', title: '设备指标' },
  { code: 'topology', path: '/topology', title: '设备拓扑图' },
  { group: '告警与巡检' },
  { code: 'alerts', path: '/alerts', title: '告警通知' },
  { code: 'inspection', path: '/inspection', title: '系统巡检' },
  { group: '运维' },
  { code: 'batch-command', path: '/batch-command', title: 'Linux 运维控制台' },
  { code: 'network-ai-command', path: '/network-ai-command', title: '网络设备运维控制台' },
  { code: 'ai-assistant', path: '/ai-assistant', title: 'AI 运维助手' },
  { group: '系统' },
  { code: 'audit', path: '/audit', title: '操作日志' },
  { code: 'backup', path: '/backup', title: '配置备份' },
  { code: 'users', path: '/users', title: '用户管理' },
  { code: 'system', path: '/system', title: '系统设置' },
]

/** 每分钟更新一次，让问候语随系统时区的「当前小时」变化（0–4 凌晨…18–23 晚上） */
const greetingTick = ref(0)
let greetingTimer = null

/** 按系统设置时区取当前小时，再返回对应问候语；依赖 systemTimezone 与 greetingTick 以在切换时区或整点时更新 */
const greetingText = computed(() => {
  systemTimezone.value
  greetingTick.value
  const tz = systemTimezone.value || 'Asia/Shanghai'
  const hourStr = new Date().toLocaleString('en-US', { timeZone: tz, hour: 'numeric', hour12: false })
  const h = parseInt(hourStr, 10) || 0
  if (h >= 0 && h < 5) return '凌晨好'
  if (h >= 5 && h < 9) return '早上好'
  if (h >= 9 && h < 12) return '上午好'
  if (h >= 12 && h < 14) return '中午好'
  if (h >= 14 && h < 18) return '下午好'
  return '晚上好'
})

onMounted(() => {
  try {
    sidebarCollapsed.value = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1'
  } catch (_) {}
  loadSystemTimezone()
  greetingTimer = setInterval(() => { greetingTick.value += 1 }, 60 * 1000)
})
onUnmounted(() => {
  if (greetingTimer) clearInterval(greetingTimer)
})

/** 与路由守卫一致：管理员放行；否则须 allowedMenus 含该 code */
function canShowMenu(item) {
  if (!item.code) return false
  return userHasSidebarMenuAccess(currentUser.value, item.code)
}

/** 从 localStorage 读取当前用户，用于顶栏显示与菜单可见性（allowedMenus） */
function loadUser() {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.USER)
    if (raw) {
      const user = JSON.parse(raw)
      currentUser.value = user
      currentUserName.value = user.realName || user.username || ''
    } else {
      currentUser.value = null
      currentUserName.value = ''
    }
  } catch (_) {
    currentUser.value = null
    currentUserName.value = ''
  }
}

function logout() {
  try { localStorage.removeItem(STORAGE_KEYS.USER) } catch (_) {}
  router.push('/login')
}

onMounted(loadUser)

/** 路由切换时重新从 localStorage 读用户（登录后跳转、或他处更新了 netpulse_user 时侧栏与 allowedMenus 一致） */
watch(() => route.path, () => { loadUser() })
</script>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background: var(--color-bg, #f8fafc);
}
.sidebar {
  width: 248px;
  min-width: 248px;
  background: linear-gradient(180deg, #0f172a 0%, #020617 100%);
  padding: 0;
  display: flex;
  flex-direction: column;
  box-shadow: 4px 0 24px rgba(0, 0, 0, 0.12);
  transition: width 0.25s ease, min-width 0.25s ease;
  overflow: hidden;
}
.layout.sidebar-collapsed .sidebar {
  width: 0;
  min-width: 0;
  box-shadow: none;
}
.sidebar-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 0.75rem 1rem 1.25rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}
.logo {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: 0;
}
.btn-collapse {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  color: #94a3b8;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease, transform 0.15s ease;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.btn-collapse:hover {
  background: rgba(56, 189, 248, 0.12);
  color: #7dd3fc;
  border-color: rgba(56, 189, 248, 0.25);
  box-shadow: 0 2px 8px rgba(56, 189, 248, 0.15);
  transform: scale(1.02);
}
.btn-collapse:active {
  transform: scale(0.98);
}
.collapse-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.logo-icon {
  font-size: 1.25rem;
  color: #38bdf8;
  opacity: 0.95;
  filter: drop-shadow(0 0 8px rgba(56, 189, 248, 0.3));
}
.logo-text {
  font-size: 1.25rem;
  font-weight: 700;
  color: #f8fafc;
  letter-spacing: -0.03em;
}
.nav {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0.85rem 0.75rem 1rem;
  overflow-y: auto;
}
.nav-group {
  font-size: 0.6875rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #64748b;
  padding: 0.85rem 0 0.35rem;
  margin-top: 0.25rem;
}
.nav-group:first-of-type { margin-top: 0; }
.nav a {
  display: block;
  padding: 0.75rem 1rem;
  color: #94a3b8;
  font-size: 0.875rem;
  transition: background 0.2s, color 0.2s, box-shadow 0.2s, transform 0.15s;
  text-decoration: none;
  background: rgba(15, 23, 42, 0.6);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
}
.nav a:hover {
  background: rgba(30, 41, 59, 0.8);
  color: #e2e8f0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  transform: translateY(-1px);
}
.nav a.active {
  background: rgba(56, 189, 248, 0.18);
  color: #7dd3fc;
  font-weight: 600;
  border-color: rgba(56, 189, 248, 0.35);
  box-shadow: 0 4px 12px rgba(56, 189, 248, 0.15);
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  padding: 1rem 1.5rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04);
}
.btn-expand {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  height: 40px;
  padding: 0 1rem;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  color: #475569;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease, transform 0.15s ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.btn-expand:hover {
  background: #f0f9ff;
  color: #0ea5e9;
  border-color: #bae6fd;
  box-shadow: 0 2px 8px rgba(14, 165, 233, 0.12);
  transform: translateY(-1px);
}
.btn-expand:active {
  transform: translateY(0);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
.expand-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}
.expand-text {
  font-size: 0.875rem;
  font-weight: 500;
  letter-spacing: 0.02em;
}
.page-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: -0.02em;
}
.header-right { display: flex; align-items: center; gap: 0.75rem; }
.greeting { font-size: 0.875rem; color: #64748b; font-weight: 500; }
.current-user { font-size: 0.875rem; color: #334155; font-weight: 500; }
.btn-logout {
  padding: 0.45rem 0.9rem;
  font-size: 0.8125rem;
  color: #64748b;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.btn-logout:hover { background: #e2e8f0; color: #334155; }
.content {
  flex: 1;
  padding: 1.5rem;
}
</style>
