import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/index.css'

import App from './App.vue'
import router from './router'
import { setupRouterGuard } from './router/guard'
import { setupDirectives } from './directives'

const app = createApp(App)

/**
 * 全量注册 Element Plus 图标。
 *
 * 菜单图标名是后端通过 MenuVO.icon 下发的字符串（如 "DataAnalysis"），
 * 侧边栏只能用 <component :is="iconName"> 动态渲染 —— 这要求图标组件
 * 必须已经全局注册。按需引入那些图标在这里行不通。
 */
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
// 注意注册顺序：守卫里会用到 Pinia store，必须等 pinia 挂上之后再注册守卫
setupRouterGuard()
app.use(router)
app.use(ElementPlus, { locale: zhCn })
setupDirectives(app)

app.mount('#app')
