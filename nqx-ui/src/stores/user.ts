import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { RouteRecordRaw } from 'vue-router'
import router from '@/router'
import { buildRoutes } from '@/router/dynamic'
import { getInfo, getMenus, login as loginApi, logout as logoutApi } from '@/api/auth'
import type { AdminInfo, MenuItem, MenuVO } from '@/types/system'
import {
  clearAuthStorage,
  getCachedMenus,
  getCachedUser,
  getToken,
  removeToken,
  setCachedMenus,
  setCachedUser,
  setToken
} from '@/utils/auth'

/** 后端约定的超级管理员角色标识，拥有它即视为拥有全部权限 */
const SUPER_ADMIN = 'SUPER_ADMIN'

export const useUserStore = defineStore('user', () => {
  /* ------------------------------ 状态 ------------------------------ */

  const token = ref<string>(getToken())
  // 用户信息与菜单都先从缓存里恢复：这样刷新页面时侧边栏能立刻渲染，
  // 不会出现「白屏 → 请求回来 → 菜单突然冒出来」的闪动
  const userInfo = ref<AdminInfo | null>(getCachedUser<AdminInfo>())
  const menus = ref<MenuVO[]>(getCachedMenus<MenuVO[]>() ?? [])
  /** 侧边栏用的菜单树（路径已拼完整） */
  const menuItems = ref<MenuItem[]>([])
  /** 动态路由是否已注册，刷新页面恢复的核心标志位 */
  const routesLoaded = ref(false)

  /** 已注册的动态路由名，退出登录时按名移除，避免换账号后残留旧菜单 */
  const addedRouteNames: string[] = []

  /* ------------------------------ 计算属性 ------------------------------ */

  const permissions = computed<string[]>(() => userInfo.value?.permissions ?? [])
  const roles = computed<string[]>(() => userInfo.value?.roles ?? [])
  const isSuperAdmin = computed(() => roles.value.includes(SUPER_ADMIN))
  const nickname = computed(() => userInfo.value?.realName || userInfo.value?.username || '未登录')

  /* ------------------------------ 行为 ------------------------------ */

  /**
   * 是否拥有某个权限码。
   *
   * 超级管理员直接放行：后端的权限码集合虽然齐全，但它是数据驱动的，
   * 新增权限时若忘了给超管补数据，前端会把超管自己的按钮藏掉 ——
   * 这个口子在后端 @SaCheckPermission 那侧是同样的处理方式。
   */
  function hasPermission(code?: string | string[]): boolean {
    if (!code) return true
    if (isSuperAdmin.value) return true
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((c) => permissions.value.includes(c))
  }

  /** 登录：拿到 token 后立刻落盘，后续请求拦截器才能带上它 */
  async function login(username: string, password: string): Promise<void> {
    const res = await loginApi({ username, password })
    token.value = res.token
    setToken(res.token)
    userInfo.value = res.user
    setCachedUser(res.user)
  }

  /** 拉取当前登录人信息（刷新页面时用，保证权限码是最新的） */
  async function fetchUserInfo(): Promise<AdminInfo> {
    const info = await getInfo()
    userInfo.value = info
    setCachedUser(info)
    return info
  }

  /**
   * 拉取菜单并注册动态路由。
   *
   * 刷新页面丢失动态路由是这类后台的经典坑：路由是运行时 addRoute 加进去的，
   * 浏览器刷新后内存里的路由表被清空，只剩静态路由，于是访问 /product/list 直接 404。
   * 解决办法就是每次进入受保护路由前，检查 `routesLoaded`，
   * 未加载则重新取菜单 → 重新 addRoute → 再重新导航一次。
   */
  async function setupDynamicRoutes(): Promise<RouteRecordRaw[]> {
    let menuList: MenuVO[] = []

    try {
      menuList = await getMenus()
      // 请求成功才更新缓存，避免后端临时故障时用空菜单覆盖掉好数据
      menus.value = menuList
      setCachedMenus(menuList)
    } catch (error) {
      // 后端不可用时退化成缓存菜单，至少让已登录用户还能看到导航骨架
      const cached = getCachedMenus<MenuVO[]>()
      if (cached && cached.length) {
        console.warn('[动态路由] 菜单接口请求失败，已回退到本地缓存菜单', error)
        menuList = cached
        menus.value = cached
      } else {
        throw error
      }
    }

    const { routes, menus: items } = buildRoutes(menuList)
    menuItems.value = items

    for (const route of routes) {
      router.addRoute(route)
      if (route.name) addedRouteNames.push(String(route.name))
    }

    routesLoaded.value = true
    return routes
  }

  /** 移除已注册的动态路由，只清内存不动静态表 */
  function resetDynamicRoutes(): void {
    for (const name of addedRouteNames) {
      if (router.hasRoute(name)) router.removeRoute(name)
    }
    addedRouteNames.length = 0
    routesLoaded.value = false
    menuItems.value = []
  }

  /** 退出登录：先告诉后端注销会话，失败也要把本地状态清干净 */
  async function logout(): Promise<void> {
    try {
      await logoutApi()
    } catch {
      // 后端注销失败（如 token 本就过期）不应阻塞用户退出，静默忽略
    }
    resetState()
  }

  /** 清空全部登录态，401 兜底与主动退出共用 */
  function resetState(): void {
    resetDynamicRoutes()
    token.value = ''
    userInfo.value = null
    menus.value = []
    removeToken()
    clearAuthStorage()
  }

  return {
    token,
    userInfo,
    menus,
    menuItems,
    routesLoaded,
    permissions,
    roles,
    isSuperAdmin,
    nickname,
    hasPermission,
    login,
    fetchUserInfo,
    setupDynamicRoutes,
    resetDynamicRoutes,
    logout,
    resetState
  }
})
