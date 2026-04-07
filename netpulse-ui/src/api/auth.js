/**
 * 认证相关 API：登录。登出由前端清除 localStorage 完成。
 */
import request from './request'

/** 登录：校验用户名密码，返回用户信息、角色列表、可见菜单（allowedMenus） */
export function login(username, password) {
  return request.post('/auth/login', { username, password })
}
