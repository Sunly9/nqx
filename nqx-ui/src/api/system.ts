import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'

/**
 * 系统管理接口，对应后端 AdminSystemUserController / AdminSystemRoleController /
 * AdminSystemPermissionController / AdminSystemLogController。
 *
 * 类型定义直接写在本文件里，而不是新建 `types/system.ts`：
 * 本次改动范围限定在 `api/system.ts` 与 `views/system/**`，
 * 而这些类型只被这 5 个页面与这几个接口使用，与接口函数放在一起
 * 反而更容易做到「改接口时顺手改类型」。
 *
 * 字段名一律以后端实际返回为准（已逐个接口实测核对）。后端开启了
 * 非空序列化（null 字段不输出），因此除主键与状态外的字段都要声明为可选，
 * 否则页面为了喂饱类型检查就得写一堆毫无意义的非空断言。
 */

/* ============================== 管理员 ============================== */

/** 管理员列表项（后端 SysUserVO）。注意：后端不会返回 password 字段 */
export interface SysUserVO {
  id: number
  username: string
  realName?: string
  phone?: string
  email?: string
  avatar?: string
  /** 0禁用 1启用 */
  status: number
  lastLoginTime?: string
  lastLoginIp?: string
  remark?: string
  createTime?: string
  /** 已分配的角色 ID，供「分配角色」弹窗回填 */
  roleIds?: number[]
  /** 已分配的角色名称，供列表直接展示 */
  roleNames?: string[]
}

export interface SysUserQuery {
  pageNum: number
  pageSize: number
  username?: string
  realName?: string
  status?: number
}

export interface SysUserSaveBody {
  id?: number
  /** 仅新增时生效：修改接口会忽略它（账号不可改） */
  username: string
  /** 仅新增时生效：修改密码请走 resetSysUserPassword */
  password?: string
  realName: string
  phone?: string
  email?: string
  avatar?: string
  status?: number
  remark?: string
}

/* ============================== 角色 ============================== */

/** 角色列表项（后端 SysRoleVO） */
export interface SysRoleVO {
  id: number
  roleCode: string
  roleName: string
  description?: string
  sort?: number
  /** 0禁用 1启用 */
  status: number
  createTime?: string
  /** 关联的管理员数量，用于提前提示「这个角色还不能删」 */
  userCount?: number
}

export interface SysRoleQuery {
  pageNum: number
  pageSize: number
  roleCode?: string
  roleName?: string
  status?: number
}

export interface SysRoleSaveBody {
  id?: number
  roleCode: string
  roleName: string
  description?: string
  sort?: number
  status?: number
}

/* ============================== 权限菜单 ============================== */

/** 权限树节点（后端 SysPermissionVO） */
export interface SysPermissionVO {
  id: number
  parentId: number
  permName: string
  permCode?: string
  /** 1目录 2菜单 3按钮 */
  permType: number
  path?: string
  component?: string
  icon?: string
  sort?: number
  /** 0隐藏 1显示 */
  visible?: number
  /** 0禁用 1启用 */
  status?: number
  children?: SysPermissionVO[]
}

export interface SysPermissionSaveBody {
  id?: number
  parentId: number
  permName: string
  permCode?: string
  permType: number
  path?: string
  component?: string
  icon?: string
  sort?: number
  visible?: number
  status?: number
}

/** 权限类型下拉项，与后端 perm_type 一一对应 */
export const PERM_TYPE_OPTIONS = [
  { label: '目录', value: 1 },
  { label: '菜单', value: 2 },
  { label: '按钮', value: 3 }
] as const

/* ============================== 日志 ============================== */

/** 操作日志列表项（后端 SysOperationLogVO）—— 不含 requestParam 等大字段 */
export interface SysOperationLogVO {
  id: number
  traceId?: string
  userId?: number
  /** 1小程序用户 2管理员 3系统 */
  userType?: number
  userName?: string
  module?: string
  operation?: string
  method?: string
  requestUrl?: string
  requestMethod?: string
  ip?: string
  location?: string
  /** 0失败 1成功 */
  status: number
  errorMsg?: string
  costTime?: number
  createTime?: string
}

/** 操作日志详情（后端 SysOperationLogDetailVO）—— 比列表多了两个大字段 */
export interface SysOperationLogDetailVO extends SysOperationLogVO {
  /** 请求参数（JSON，已脱敏、已截断到 4000 字符） */
  requestParam?: string
  /** 响应结果（JSON，仅接口显式开启记录时才有值） */
  responseResult?: string
}

/** 登录日志列表项（后端 SysLoginLogVO） */
export interface SysLoginLogVO {
  id: number
  userId?: number
  userType?: number
  userName?: string
  ip?: string
  location?: string
  browser?: string
  os?: string
  /** 0失败 1成功 */
  status: number
  msg?: string
  loginTime?: string
}

/**
 * 日志检索条件（两张日志表共用，后端也是同一个 LogQuery）。
 *
 * 日期用 `YYYY-MM-DD` 字符串（后端是 LocalDate），区间为闭区间：
 * 选了截止日就包含当天全天。
 */
export interface SysLogQuery {
  pageNum: number
  pageSize: number
  /** 仅操作日志使用 */
  module?: string
  userName?: string
  status?: number
  startDate?: string
  endDate?: string
}

/** 用户类型标签：两张日志表共用同一套约定（1小程序用户 2管理员 3系统） */
export const OPERATOR_TYPE_OPTIONS = {
  1: { label: '小程序用户', tag: 'warning' },
  2: { label: '管理员', tag: 'primary' },
  3: { label: '系统', tag: 'info' }
} as const

/* ============================== 接口 ============================== */

// ---------- 管理员管理 ----------

export function pageSysUsers(params: SysUserQuery) {
  return http.get<PageResult<SysUserVO>>('/admin/system/user/page', params)
}

export function createSysUser(data: SysUserSaveBody) {
  return http.post<number>('/admin/system/user', data)
}

export function updateSysUser(data: SysUserSaveBody) {
  return http.put<void>('/admin/system/user', data)
}

/** 启用/禁用。后端把 status 设计成 query 参数而非 body，这里要跟着走 */
export function changeSysUserStatus(id: number, status: number) {
  return http.put<void>(`/admin/system/user/${id}/status`, undefined, { params: { status } })
}

export function resetSysUserPassword(id: number, password: string) {
  return http.put<void>(`/admin/system/user/${id}/reset-password`, { password })
}

export function assignSysUserRoles(id: number, roleIds: number[]) {
  return http.put<void>(`/admin/system/user/${id}/roles`, { roleIds })
}

export function getSysUserRoleIds(id: number) {
  return http.get<number[]>(`/admin/system/user/${id}/roles`)
}

export function deleteSysUser(id: number) {
  return http.delete<void>(`/admin/system/user/${id}`)
}

// ---------- 角色管理 ----------

export function pageSysRoles(params: SysRoleQuery) {
  return http.get<PageResult<SysRoleVO>>('/admin/system/role/page', params)
}

/** 全部角色（不分页），供「分配角色」下拉使用；含已禁用角色 */
export function listSysRoles() {
  return http.get<SysRoleVO[]>('/admin/system/role/list')
}

export function createSysRole(data: SysRoleSaveBody) {
  return http.post<number>('/admin/system/role', data)
}

export function updateSysRole(data: SysRoleSaveBody) {
  return http.put<void>('/admin/system/role', data)
}

export function deleteSysRole(id: number) {
  return http.delete<void>(`/admin/system/role/${id}`)
}

export function getSysRolePermissionIds(id: number) {
  return http.get<number[]>(`/admin/system/role/${id}/permissions`)
}

export function assignSysRolePermissions(id: number, permissionIds: number[]) {
  return http.put<void>(`/admin/system/role/${id}/permissions`, { permissionIds })
}

// ---------- 权限菜单 ----------

export function getSysPermissionTree() {
  return http.get<SysPermissionVO[]>('/admin/system/permission/tree')
}

export function createSysPermission(data: SysPermissionSaveBody) {
  return http.post<number>('/admin/system/permission', data)
}

export function updateSysPermission(data: SysPermissionSaveBody) {
  return http.put<void>('/admin/system/permission', data)
}

export function deleteSysPermission(id: number) {
  return http.delete<void>(`/admin/system/permission/${id}`)
}

// ---------- 日志 ----------

export function pageOperationLogs(params: SysLogQuery) {
  return http.get<PageResult<SysOperationLogVO>>('/admin/system/log/operation/page', params)
}

/** 详情才会返回 requestParam / responseResult，列表刻意不返回以控制响应体大小 */
export function getOperationLogDetail(id: number) {
  return http.get<SysOperationLogDetailVO>(`/admin/system/log/operation/${id}`)
}

export function pageLoginLogs(params: SysLogQuery) {
  return http.get<PageResult<SysLoginLogVO>>('/admin/system/log/login/page', params)
}
