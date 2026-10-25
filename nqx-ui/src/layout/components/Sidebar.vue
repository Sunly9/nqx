<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import SidebarItem from './SidebarItem.vue'

/**
 * 左侧菜单。
 *
 * 菜单数据完全来自后端 /admin/auth/menus，前端不做任何硬编码 ——
 * 新增一个菜单只需后端插一条 sys_permission 记录。
 * 代价是必须在 src/views 下放好对应的 .vue 文件，否则动态路由会加载失败。
 */
const route = useRoute()
const appStore = useAppStore()
const userStore = useUserStore()

// 高亮项用当前路由路径。用 path 而不是 name，是因为 name 是前端根据 path
// 生成的（后端并不下发 name），两者本就一一对应，用 path 更直观
const activeMenu = computed(() => route.path)
</script>

<template>
  <div class="sidebar">
    <div class="sidebar-logo">
      <img src="/logo.svg" alt="logo" class="sidebar-logo-img" />
      <span v-show="!appStore.sidebarCollapsed" class="sidebar-logo-text">纽启新生</span>
    </div>

    <el-scrollbar class="sidebar-scroll">
      <el-menu
        :default-active="activeMenu"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        router
        unique-opened
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <SidebarItem v-for="item in userStore.menuItems" :key="item.path" :item="item" />
      </el-menu>

      <el-empty
        v-if="!userStore.menuItems.length"
        description="暂无可用菜单"
        :image-size="60"
        class="sidebar-empty"
      />
    </el-scrollbar>
  </div>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: #304156;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 56px;
  flex-shrink: 0;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}

.sidebar-logo-img {
  width: 26px;
  height: 26px;
}

.sidebar-logo-text {
  white-space: nowrap;
}

.sidebar-scroll {
  flex: 1;
  overflow: hidden;
}

.sidebar-empty {
  padding: 16px 0;
}

:deep(.el-menu) {
  border-right: none;
}

/* 折叠时 Element Plus 默认会把子菜单标题文字隐藏，这里补一点左侧间距让图标居中 */
:deep(.el-menu--collapse) {
  width: 64px;
}
</style>
