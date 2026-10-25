import { ElMessage } from 'element-plus'
import router from './index'
import { useUserStore } from '@/stores/user'
import { setUnauthorizedHandler } from '@/utils/auth'
import { isMobileViewport, preferDesktop } from '@/utils/device'

/** 无需登录即可访问的路径 */
const WHITE_LIST = ['/login', '/404', '/403']

/** 移动端路由前缀 */
const MOBILE_PREFIX = '/m'

/**
 * 桌面端的「首页」候选路径。
 *
 * 为什么要列成一个数组：登录成功后、以及访问根路径 `/` 时，
 * 实际落地的是这些路径之一。移动端要在**这些入口**上做一次转向，
 * 而不是把所有桌面路由都拦掉 —— 用户主动点了"电脑版"之后
 * 仍然要能正常浏览桌面页面。
 */
const DESKTOP_HOME_PATHS = ['/', '/dashboard/index']

/** 统一设置浏览器标题，方便多标签页时区分 */
function setDocumentTitle(title?: string) {
  const base = '纽启新生管理后台'
  document.title = title ? `${title} · ${base}` : base
}

/**
 * 注册全局路由守卫。
 *
 * 这里承担了动态路由的「刷新恢复」职责：只要 token 在、路由表还没建，
 * 就先取菜单 → addRoute → 再以同样的目标重新导航一次。
 * 少了最后那次重新导航，第一次进入深链接会停在 404 —— 
 * 因为本次匹配早已在路由表被扩充之前就完成了。
 */
export function setupRouterGuard() {
  router.beforeEach(async (to) => {
    const userStore = useUserStore()

    // 登录页不做鉴权，其它页面统一交给下面的分支
    if (!userStore.token) {
      if (WHITE_LIST.includes(to.path)) return true
      return { path: '/login', query: { redirect: to.fullPath } }
    }

    // 已登录还去登录页，直接送回首页，避免出现「登录成功但停在登录页」
    if (to.path === '/login') return { path: '/' }

    /*
     * 手机访问桌面首页 → 自动切到手机版。
     *
     * 为什么只在「首页」这一层转向，而不是拦截所有桌面路由：
     *
     *   · 用户在手机上点"切换到电脑版"之后，明确表达了要用桌面界面，
     *     此时仍然会落到 /dashboard/index 这类路径 —— 如果无条件重定向回 /m，
     *     那个按钮就永远点不动；
     *   · 而深链接（比如别人发来的 /product/list?x=1）应该原样打开，
     *     不该被强行改道。
     *
     * 所以规则是：**只把"进入系统"这个入口判定一次**，进了之后尊重用户的选择。
     */
    if (!preferDesktop() && isMobileViewport() && DESKTOP_HOME_PATHS.includes(to.path)) {
      return { path: '/m/home' }
    }

    /*
     * 移动端路由不依赖后端菜单。
     *
     * 桌面端的动态路由是拿菜单生成的，但 /m/* 是固定路由（见 router/mobile.ts）。
     * 如果也走一遍 setupDynamicRoutes，每次冷启动都要多一次菜单请求，
     * 而手机端根本用不到那份菜单树。
     *
     * 但「用户信息」仍然要拉：手机版的"我的"页要显示昵称、角色和权限码。
     */
    const isMobileRoute = to.path === MOBILE_PREFIX || to.path.startsWith(MOBILE_PREFIX + '/')
    if (isMobileRoute) {
      if (!userStore.userInfo) {
        try {
          await userStore.fetchUserInfo()
        } catch (error) {
          console.error('[路由守卫] 用户信息加载失败', error)
          userStore.resetState()
          return { path: '/login', query: { redirect: to.fullPath } }
        }
      }
      return true
    }

    if (!userStore.routesLoaded) {
      try {
        await userStore.setupDynamicRoutes()
        // 用户信息（权限码来源）在缓存缺失时补拉一次；
        // 有缓存时不阻塞首屏，权限码的时效性由 401 兜底
        if (!userStore.userInfo) {
          await userStore.fetchUserInfo()
        }
        // 路由表已扩充，用原样的目标再解析一次。
        // 这里必须用 to.fullPath —— 首次匹配时目标路径还没被任何动态路由命中，
        // 拿到的是 catch-all 的结果，只有拿到完整原始路径重放才能正确落位。
        return { path: to.fullPath, replace: true }
      } catch (error) {
        console.error('[路由守卫] 菜单加载失败', error)
        userStore.resetState()
        ElMessage.error('获取菜单失败，请重新登录')
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }

    // 路由级权限校验：后端下发的菜单本身已按权限过滤，
    // 这一层是为了挡住「手输 URL 访问未授权菜单」的情况
    if (to.meta.permCode && !userStore.hasPermission(to.meta.permCode)) {
      return { path: '/403' }
    }

    return true
  })

  router.afterEach((to) => {
    setDocumentTitle(to.meta.title)
  })

  // 把「token 失效后跳登录」注册给 utils/auth，
  // 让 request.ts 不必 import router（否则会形成循环依赖）
  setUnauthorizedHandler(() => {
    const userStore = useUserStore()
    userStore.resetState()
    const current = router.currentRoute.value
    if (current.path !== '/login') {
      router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  })
}
