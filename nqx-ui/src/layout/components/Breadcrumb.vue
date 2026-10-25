<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'

/**
 * 顶部面包屑。
 *
 * 数据来源是 route.matched —— Vue Router 会把从根到当前路由的整条链都放在这里，
 * 而每个节点的 meta.title 是动态路由注册时写进去的菜单名。
 * 因此面包屑天然与后端菜单一致，无需另外维护一份层级表。
 */
const route = useRoute()
const appStore = useAppStore()

/** 过滤掉没有标题的层级（如只有 redirect 的根路由），并去掉重复项 */
const breadcrumbs = computed(() => {
  const items = route.matched
    .filter((item) => item.meta && item.meta.title)
    .map((item) => ({ title: item.meta.title as string, path: item.path }))

  return items.filter((item, index) => index === 0 || item.title !== items[index - 1].title)
})
</script>

<template>
  <el-breadcrumb class="breadcrumb" separator="/">
    <el-breadcrumb-item v-if="!appStore.sidebarCollapsed" :to="{ path: '/' }">首页</el-breadcrumb-item>
    <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
      {{ item.title }}
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.breadcrumb {
  display: inline-flex;
  align-items: center;
  height: 100%;
  font-size: 14px;
}
</style>
