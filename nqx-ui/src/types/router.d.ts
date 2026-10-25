import type { RouteMeta } from 'vue-router'

/**
 * 扩展路由 meta 的类型声明。
 *
 * 不加这段，`to.meta.title` 在 TS 里是 unknown，每个页面都要写一次 as string —— 
 * 把类型补在源头，比在几十处调用点反复断言干净得多。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 菜单与面包屑上显示的中文标题 */
    title?: string
    /** Element Plus 图标组件名，由后端菜单下发 */
    icon?: string
    /** 访问该路由所需的权限码 */
    permCode?: string
    /** 是否在菜单里隐藏（登录页等） */
    hidden?: boolean
  }
}

export {}
