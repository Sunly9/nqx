import type { RouteRecordRaw } from 'vue-router'

/**
 * 移动端路由（`/m/*`）。
 *
 * <h2>为什么是「静态注册」而不是走后端菜单</h2>
 *
 * 桌面端的业务路由全部由后端菜单动态生成（`userStore.setupDynamicRoutes()`），
 * 好处是权限一变菜单就变。但移动端的情况不同：
 *
 *   · 手机屏幕只能放 4 个底部 Tab，不可能把 8 个菜单全铺开；
 *   · 移动端页面是**为手机重新设计的**，和桌面页面不是同一个组件，
 *     后端菜单里根本没有这些路径；
 *   · 如果让后端菜单来驱动，还得在菜单表里加一堆 `/m/xxx` 的隐藏节点 ——
 *     为了前端布局去改权限数据，本末倒置。
 *
 * 所以移动端用固定路由。**权限校验并没有被绕过**：
 *   1. 路由守卫统一要求登录（token 不存在直接踢到 login）；
 *   2. 每个页面调用的后端接口仍然走 `@SaCheckPermission`，
 *      没有权限的账号进得来页面、但操作会被后端 403 拦住。
 *
 * 这就是"前端导航 ≠ 权限边界"的正确分工：前端只负责不方便，
 * 真正的边界永远在后端。
 */
export const mobileRoutes: RouteRecordRaw[] = [
  {
    path: '/m',
    component: () => import('@/views/m/layout/index.vue'),
    meta: { title: '移动端', hidden: true },
    children: [
      {
        path: '',
        // 进入 /m 默认落到工作台
        redirect: '/m/home'
      },
      {
        path: 'home',
        name: 'MHome',
        component: () => import('@/views/m/home/index.vue'),
        meta: { title: '工作台', tab: true }
      },
      {
        path: 'product',
        name: 'MProduct',
        component: () => import('@/views/m/product/list/index.vue'),
        meta: { title: '商品', tab: true }
      },
      {
        path: 'product/edit/:id?',
        name: 'MProductEdit',
        component: () => import('@/views/m/product/edit/index.vue'),
        meta: { title: '编辑商品' }
      },
      {
        path: 'orders',
        name: 'MOrders',
        component: () => import('@/views/m/order/index.vue'),
        meta: { title: '订单', tab: true }
      },
      {
        path: 'mine',
        name: 'MMine',
        component: () => import('@/views/m/mine/index.vue'),
        meta: { title: '我的', tab: true }
      }
    ]
  }
]
