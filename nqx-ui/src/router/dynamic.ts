import type { RouteRecordRaw } from 'vue-router'
import type { MenuItem, MenuVO } from '@/types/system'

/**
 * 动态路由构建。
 *
 * 后端只下发「组件路径字符串」（如 `product/list/index`），
 * 前端必须自己把字符串映射成真实组件 —— 这是动态路由的核心一步。
 *
 * 用 import.meta.glob 而不是 `() => import('../views/' + path + '.vue')`：
 * Vite 是构建期打包工具，只有 glob 这种「静态可分析」的写法才能把组件纳入产物；
 * 用变量拼字符串的方式，Rollup 无法静态分析，运行时会报模块找不到。
 */
const viewModules = import.meta.glob('../views/**/*.vue')

/** 把后端的组件路径转换成 glob 的 key */
function resolveComponent(componentPath?: string) {
  if (!componentPath) return undefined
  // 兼容后端可能带 .vue 后缀或前导斜杠的写法，统一归一化后再查找
  const normalized = componentPath.replace(/^\/+/, '').replace(/\.vue$/, '')
  return viewModules[`../views/${normalized}.vue`]
}

/** 由路径生成唯一路由名，用于 addRoute 去重与跳转 */
function toRouteName(fullPath: string): string {
  return fullPath.replace(/^\/+/, '').replace(/[/:-]/g, '-') || 'root'
}

/** 拼接父子路径，处理两端的斜杠，避免出现 `/order//recycle` 这种脏路径 */
function joinPath(parent: string, path: string): string {
  if (!path) return parent
  if (path.startsWith('/')) return path
  return `${parent}/${path}`.replace(/\/{2,}/g, '/')
}

export interface BuiltRoutes {
  /** 可直接交给 router.addRoute 的路由记录 */
  routes: RouteRecordRaw[]
  /** 侧边栏渲染用的菜单树 */
  menus: MenuItem[]
}

/**
 * 菜单 → 路由。
 *
 * 目录节点（type=1）挂 Layout 并带 redirect 指向第一个子菜单，
 * 这样用户点目录名时不会停在一个空白页上；
 * 菜单节点（type=2）才挂真实业务组件。
 */
export function buildRoutes(menuList: MenuVO[], parentPath = ''): BuiltRoutes {
  const routes: RouteRecordRaw[] = []
  const menus: MenuItem[] = []

  // 后端虽然带了 sort，但为稳妥起见仍在前端按 sort 排一次，
  // 避免后端某次改动漏了 ORDER BY 导致菜单顺序随机
  const sorted = [...(menuList || [])].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

  for (const menu of sorted) {
    const fullPath = joinPath(parentPath, menu.path)
    const children = menu.children ?? []
    const name = toRouteName(fullPath)

    if (menu.type === 1) {
      // 目录：没有具体组件，只作为分组容器
      const built = buildRoutes(children, fullPath)
      const firstChild = built.menus[0]?.path

      routes.push({
        path: fullPath,
        name,
        // 目录默认重定向到第一个子菜单，否则点目录会出现空白内容区
        redirect: firstChild || undefined,
        // Layout 用懒加载：登录页不需要它，没必要打进首屏包
        component: () => import('@/layout/index.vue'),
        meta: { title: menu.name, icon: menu.icon },
        children: built.routes
      } as RouteRecordRaw)

      menus.push({
        path: fullPath,
        title: menu.name,
        icon: menu.icon,
        children: built.menus
      })
    } else {
      // 菜单：真正的页面
      const comp = resolveComponent(menu.component)
      if (!comp) {
        // 组件缺失时不能让整个 addRoute 抛错，否则后面的菜单全部注册不上。
        // 这里退化成占位组件并把问题暴露在控制台，保证后台整体可用。
        console.error(
          `[动态路由] 菜单「${menu.name}」的组件 views/${menu.component}.vue 不存在，已用占位页替代`
        )
      }

      routes.push({
        path: fullPath,
        name,
        component: comp || (() => import('@/views/error/missing.vue')),
        meta: {
          title: menu.name,
          icon: menu.icon,
          permCode: menu.permCode
        }
      } as RouteRecordRaw)

      menus.push({
        path: fullPath,
        title: menu.name,
        icon: menu.icon
      })
    }
  }

  return { routes, menus }
}
