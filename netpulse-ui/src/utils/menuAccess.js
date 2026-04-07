/**
 * 与后端 MenuConstants / 登录 user 一致：侧栏与路由守卫共用。
 * 管理员（角色码 ADMIN，忽略大小写）视为拥有全部菜单，避免 allowedMenus 与 role_menu 不同步时无法进入页面。
 */
function isAdminUser(user) {
  if (!user || !Array.isArray(user.roles)) return false
  return user.roles.some((r) => String(r || '').trim().toUpperCase() === 'ADMIN')
}

/**
 * 路由是否可进入该 menuCode（与旧逻辑兼容：allowedMenus 为空数组时不拦截，避免历史登录数据无字段时死循环）
 */
export function userHasRouteMenuAccess(user, menuCode) {
  if (!menuCode) return true
  if (!user) return false
  if (isAdminUser(user)) return true
  const allowed = user.allowedMenus
  if (!Array.isArray(allowed)) return false
  if (allowed.length === 0) return true
  if (allowed.includes(menuCode)) return true
  if (menuCode === 'inspection') {
    return allowed.includes('system-inspection') || allowed.includes('inspection-reports')
  }
  return false
}

/** 侧栏是否显示该菜单：无 allowedMenus 或非管理员且列表不含则隐藏 */
export function userHasSidebarMenuAccess(user, menuCode) {
  if (!menuCode) return false
  if (!user) return false
  if (isAdminUser(user)) return true
  const allowed = user.allowedMenus
  if (!Array.isArray(allowed) || allowed.length === 0) return false
  if (allowed.includes(menuCode)) return true
  // 合并菜单后兼容旧权限：曾勾选 system-inspection / inspection-reports 的仍可见「巡检」
  if (menuCode === 'inspection') {
    return allowed.includes('system-inspection') || allowed.includes('inspection-reports')
  }
  return false
}
