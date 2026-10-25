import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { mobileRoutes } from './mobile'

/**
 * 静态路由：不需要任何权限就能访问的页面。
 *
 * 业务路由全部由后端菜单动态注册，因此这里只有登录页、主框架壳与错误页。
 * 注意 catch-all 的 404 也放在静态表里：动态路由是用 addRoute 逐个追加的，
 * 而 Vue Router 按路径权重匹配而非注册顺序，因此后加的 /product/list
 * 不会被先注册的 /:pathMatch(.*)* 抢走。
 */
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', hidden: true }
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', hidden: true }
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', hidden: true }
  },
  {
    path: '/',
    name: 'Root',
    // 根路径重定向到看板；若当前账号没有看板权限，守卫会把它落到 403
    redirect: '/dashboard/index'
  },
  /*
   * 移动端路由（/m/*）。
   *
   * 用展开运算符放在 catch-all **之前**：Vue Router 本身按路径权重匹配，
   * 顺序其实不影响结果，但把具体路由写在通配路由前面是更稳妥的习惯 ——
   * 将来若有人把 catch-all 改成 redirect，就会立刻出问题。
   */
  ...mobileRoutes,
  {
    path: '/:pathMatch(.*)*',
    name: 'CatchAll',
    /**
     * 必须直接渲染 404 组件，而**不能**写成 `redirect: '/404'`。
     *
     * 原因与动态路由的刷新恢复直接相关：路由守卫需要拿到「用户本来想访问的路径」，
     * 才能在建好路由表之后重新导航过去。若这里用 redirect，
     * 重定向在守卫之前就已经发生，守卫看到的 `to.path` 会变成 `/404`，
     * 于是重新导航的目标从 `/product/list` 被替换成 `/404` ——
     * 表现就是「刷新任意业务页面都跳到 404」。
     *
     * 用组件渲染则 URL 保持原样（这本身也是更常见的 404 处理方式），
     * 守卫拿到的 `to.path` 仍是用户真正想要的地址。
     */
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', hidden: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior: () => ({ top: 0 })
})

export default router
