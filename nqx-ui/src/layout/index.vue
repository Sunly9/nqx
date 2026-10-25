<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from '@/stores/app'
import Sidebar from './components/Sidebar.vue'
import Navbar from './components/Navbar.vue'
import TagsView from './components/TagsView.vue'

/**
 * 主框架。
 *
 * 它被「动态路由里的每个一级目录」复用为容器组件（见 router/dynamic.ts），
 * 因此左侧菜单只有一个实例，切换菜单时布局不会重建。
 */
const appStore = useAppStore()

const asideWidth = computed(() =>
  appStore.sidebarCollapsed ? 'var(--nqx-sidebar-collapsed-width)' : 'var(--nqx-sidebar-width)'
)
</script>

<template>
  <el-container class="layout-root">
    <el-aside :width="asideWidth" class="layout-aside">
      <Sidebar />
    </el-aside>

    <el-container class="layout-body">
      <el-header height="var(--nqx-header-height)" class="layout-header">
        <Navbar />
      </el-header>

      <TagsView />

      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <transition name="fade-transform" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-root {
  height: 100vh;
  overflow: hidden;
}

.layout-aside {
  /* 折叠动画交给 el-aside 的 width 过渡，比 JS 逐帧计算省事 */
  transition: width 0.25s ease;
  overflow: hidden;
  background-color: #304156;
}

.layout-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.layout-header {
  padding: 0;
  flex-shrink: 0;
}

.layout-main {
  flex: 1;
  padding: 0;
  overflow-y: auto;
  background-color: #f0f2f5;
}

.fade-transform-enter-active,
.fade-transform-leave-active {
  transition: all 0.2s ease;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateX(-12px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateX(12px);
}
</style>
