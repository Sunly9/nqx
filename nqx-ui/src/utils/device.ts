/**
 * 设备 / 视口判定工具。
 *
 * 单独成文件的原因：**布局组件和路由守卫都要用同一套判定**。
 * 写在组件里就只能被组件用；写在守卫里又会让组件 import 守卫（形成循环依赖）。
 *
 * 判定策略上刻意简单：只看视口宽度，不看 UA。
 *
 *   · 用 UA 判断"是不是手机"会漏掉平板和折叠屏，还会把"手机浏览器请求桌面站点"
 *     这种正常行为搞坏；
 *   · 视口宽度才是真正决定"这个界面放不放得下"的东西。
 *
 * 768px 是 Element Plus 自己的 md 断点，跟它保持一致，避免出现
 * "组件认为是桌面、路由认为是手机"这种自相矛盾的状态。
 */

/** 视为移动端的最大视口宽度（含） */
export const MOBILE_MAX_WIDTH = 768

/** localStorage 键：用户明确选择用电脑版 */
export const PREFER_DESKTOP_KEY = 'nqx_prefer_desktop'

/** 当前视口是不是移动端尺寸 */
export function isMobileViewport(): boolean {
  // SSR 或测试环境里没有 window，按桌面处理
  if (typeof window === 'undefined') return false
  return window.innerWidth <= MOBILE_MAX_WIDTH
}

/** 用户是否明确选过"用电脑版" */
export function preferDesktop(): boolean {
  try {
    return localStorage.getItem(PREFER_DESKTOP_KEY) === '1'
  } catch {
    // 隐私模式下 localStorage 可能抛异常，当作没选过
    return false
  }
}

/** 记住"用电脑版"的偏好 */
export function setPreferDesktop(): void {
  try {
    localStorage.setItem(PREFER_DESKTOP_KEY, '1')
  } catch {
    /* 存不进去就算了，下次重新判定即可 */
  }
}

/** 清除偏好（比如从"我的"页面点"回到手机版"） */
export function clearPreferDesktop(): void {
  try {
    localStorage.removeItem(PREFER_DESKTOP_KEY)
  } catch {
    /* 同上 */
  }
}
