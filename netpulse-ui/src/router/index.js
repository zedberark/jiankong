/**
 * 前端路由：登录页为公开，其余需本地存在 netpulse_user 才可访问；
 * 未登录访问受保护页会带上 from 参数以便登录后跳回。可见菜单与页面访问由角色权限模板控制。
 */
import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../views/Layout.vue'
import { STORAGE_KEYS } from '../utils/constants'
import { userHasRouteMenuAccess } from '../utils/menuAccess'

const LOGIN_PATH = '/login'
const USER_STORAGE_KEY = STORAGE_KEYS.USER

const routes = [
  { path: LOGIN_PATH, name: 'Login', component: () => import('../views/Login.vue'), meta: { title: '登录', public: true } },
  {
    path: '/',
    component: Layout,
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '首页仪表盘', menuCode: 'dashboard' } },
      { path: 'devices', name: 'Devices', component: () => import('../views/DeviceList.vue'), meta: { title: '设备管理', menuCode: 'devices' } },
      { path: 'topology', name: 'Topology', component: () => import('../views/DeviceTopology.vue'), meta: { title: '设备拓扑图', menuCode: 'topology' } },
      { path: 'batch-command', name: 'BatchCommand', component: () => import('../views/BatchCommand.vue'), meta: { title: 'Linux 运维控制台', menuCode: 'batch-command' } },
      { path: 'network-ai-command', name: 'NetworkAiCommand', component: () => import('../views/NetworkAiCommand.vue'), meta: { title: '网络设备运维控制台', menuCode: 'network-ai-command' } },
      { path: 'alerts', name: 'Alerts', component: () => import('../views/AlertNotify.vue'), meta: { title: '告警通知', menuCode: 'alerts' } },
      { path: 'metrics', name: 'RealtimeMetrics', component: () => import('../views/RealtimeMetrics.vue'), meta: { title: '设备指标', menuCode: 'metrics' } },
      { path: 'inspection', name: 'InspectionCenter', component: () => import('../views/InspectionCenter.vue'), meta: { title: '系统巡检', menuCode: 'inspection' } },
      { path: 'system-inspection', redirect: '/inspection' },
      { path: 'inspection-reports', redirect: '/inspection?tab=reports' },
      { path: 'audit', name: 'Audit', component: () => import('../views/OperationAudit.vue'), meta: { title: '操作日志', menuCode: 'audit' } },
      { path: 'backup', name: 'Backup', component: () => import('../views/ConfigBackup.vue'), meta: { title: '配置备份', menuCode: 'backup' } },
      { path: 'ai-assistant', name: 'AiAssistant', component: () => import('../views/AiAssistant.vue'), meta: { title: 'AI 运维助手', menuCode: 'ai-assistant' } },
      { path: 'users', name: 'Users', component: () => import('../views/UserManage.vue'), meta: { title: '用户管理', menuCode: 'users' } },
      { path: 'system', name: 'System', component: () => import('../views/SystemSettings.vue'), meta: { title: '系统设置', menuCode: 'system' } },
      { path: 'ssh/:id?', name: 'WebSsh', component: () => import('../views/WebSsh.vue'), meta: { title: 'Web SSH' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：公开页放行；已登录访问登录页则重定向到仪表盘；未登录访问受保护页跳登录并带 from
router.beforeEach((to, from, next) => {
  const isPublic = to.meta.public === true
  const hasUser = (() => {
    try {
      return !!localStorage.getItem(USER_STORAGE_KEY)
    } catch (e) { return false }
  })()
  if (isPublic) {
    if (hasUser && to.path === LOGIN_PATH) return next('/dashboard')
    return next()
  }
  if (!hasUser) return next({ path: LOGIN_PATH, query: { from: to.fullPath } })
  // 角色权限：非管理员须 allowedMenus 含对应 menuCode；管理员（roles 含 ADMIN）放行全部
  const menuCode = to.meta.menuCode
  if (menuCode) {
    try {
      const raw = localStorage.getItem(USER_STORAGE_KEY)
      const user = raw ? JSON.parse(raw) : null
      if (!userHasRouteMenuAccess(user, menuCode)) return next('/dashboard')
    } catch (e) {
      return next('/dashboard')
    }
  }
  next()
})

// 每次路由切换后更新浏览器标题
router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 监控运维系统` : '监控运维系统'
})

export default router
