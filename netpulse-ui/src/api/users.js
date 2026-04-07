/**
 * 用户与角色 API：用户增删改查、用户所属角色、角色权限模板（可分配菜单）。
 */
import request from './request'

/** 用户列表 */
export function getUsers() {
  return request.get('/users')
}

/** 某用户当前绑定的角色 ID 列表（用于编辑用户时回显角色） */
export function getUserRoles(id) {
  return request.get(`/users/${id}/roles`)
}

/** 新增用户（用户名、密码、角色等） */
export function createUser(data) {
  return request.post('/users', data)
}

/** 更新用户信息与角色绑定 */
export function updateUser(id, data) {
  return request.put(`/users/${id}`, data)
}

/** 删除用户 */
export function deleteUser(id) {
  return request.delete(`/users/${id}`)
}

/** 角色列表（管理员、运维、访客等） */
export function getRoles() {
  return request.get('/roles')
}

/** 角色权限模板：某角色可勾选的菜单 */
export function getRoleMenus(roleId) {
  return request.get(`/roles/${roleId}/menus`)
}

/** 保存某角色的权限模板（可勾选菜单编码列表，用户可见菜单为所属角色模板的并集） */
export function saveRoleMenus(roleId, menuCodes) {
  return request.put(`/roles/${roleId}/menus`, menuCodes)
}
