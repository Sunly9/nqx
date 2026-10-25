/**
 * 登录态在 localStorage 里的唯一读写入口。
 *
 * 为什么把它抽成独立模块、而不是直接写进 Pinia store？
 * request.ts 需要读 token、401 时需要清 token，而 store 又依赖 request 发请求 ——
 * 若让 request 直接 import store，就形成 request → store → api → request 的循环依赖。
 * 把「存哪儿」这件事下沉到这个不依赖任何业务模块的文件，循环自然消失。
 */

const TOKEN_KEY = 'nqx_admin_token'
/** 菜单缓存键：动态路由刷新恢复的兜底数据源 */
const MENUS_KEY = 'nqx_admin_menus'
/** 用户信息缓存键 */
const USER_KEY = 'nqx_admin_user'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function getCachedMenus<T>(): T | null {
  const raw = localStorage.getItem(MENUS_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    // 缓存被手工改坏时不应让整个后台白屏，直接当没有缓存处理
    localStorage.removeItem(MENUS_KEY)
    return null
  }
}

export function setCachedMenus(menus: unknown): void {
  localStorage.setItem(MENUS_KEY, JSON.stringify(menus))
}

export function getCachedUser<T>(): T | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export function setCachedUser(user: unknown): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAuthStorage(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(MENUS_KEY)
  localStorage.removeItem(USER_KEY)
}

/* ------------------------------------------------------------------ */

/**
 * 401 的处理器注册点。
 *
 * request.ts 不能直接 import router（会形成循环依赖），
 * 于是反过来由 router 在初始化时把「跳登录页」这个动作注册进来。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

/**
 * 触发登出。
 *
 * 用标志位做一次性开关：页面初始化往往并发发出多个请求，
 * token 失效时它们会同时收到 401，若不去重就会连弹四五个错误提示、
 * 并触发多次路由跳转。
 */
let handling = false

export function triggerUnauthorized(): void {
  if (handling) return
  handling = true
  clearAuthStorage()
  try {
    unauthorizedHandler?.()
  } finally {
    // 留出一个事件循环的时间窗，让同一批并发请求都能被这次处理吃掉
    setTimeout(() => {
      handling = false
    }, 800)
  }
}
