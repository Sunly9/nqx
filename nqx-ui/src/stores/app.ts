import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 应用级的界面状态。
 *
 * 侧边栏折叠开关放在这里而不是 Layout 组件内：Navbar 上的折叠按钮和 Sidebar
 * 是两个兄弟组件，状态若留在 Layout 里就得靠 props/emit 上下穿两层，
 * 用一个 store 反而是更少的耦合。
 */
export const useAppStore = defineStore('app', () => {
  /** 侧边栏是否折叠 */
  const sidebarCollapsed = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return { sidebarCollapsed, toggleSidebar }
})
