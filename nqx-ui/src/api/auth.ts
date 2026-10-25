import { http } from '@/utils/request'
import type { AdminInfo, AdminLoginResult, MenuVO } from '@/types/system'

/** 认证相关接口，路径与后端 AdminAuthController 一一对应 */

export function login(data: { username: string; password: string }) {
  return http.post<AdminLoginResult>('/admin/auth/login', data)
}

export function logout() {
  return http.post<null>('/admin/auth/logout')
}

export function getInfo() {
  return http.get<AdminInfo>('/admin/auth/info')
}

export function getMenus() {
  return http.get<MenuVO[]>('/admin/auth/menus')
}
