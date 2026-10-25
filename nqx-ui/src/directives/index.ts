import type { App } from 'vue'
import { permissionDirective } from './permission'

/** 全局自定义指令统一注册入口，新增指令只改这里 */
export function setupDirectives(app: App) {
  app.directive('permission', permissionDirective)
}
