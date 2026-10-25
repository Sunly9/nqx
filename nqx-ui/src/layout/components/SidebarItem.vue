<script setup lang="ts">
/**
 * 递归的菜单项渲染组件。
 *
 * 组件在 <script setup> 里可以直接用文件名引用自己（Vue 3 的隐式自引用），
 * 因此不需要额外 import，也不会出现循环导入的问题。
 */
import type { MenuItem } from '@/types/system'

defineProps<{ item: MenuItem }>()
</script>

<template>
  <!-- 有子节点 → 可折叠的子菜单；没有 → 叶子菜单项 -->
  <el-sub-menu v-if="item.children && item.children.length" :index="item.path">
    <template #title>
      <el-icon v-if="item.icon">
        <component :is="item.icon" />
      </el-icon>
      <span>{{ item.title }}</span>
    </template>

    <SidebarItem v-for="child in item.children" :key="child.path" :item="child" />
  </el-sub-menu>

  <el-menu-item v-else :index="item.path">
    <el-icon v-if="item.icon">
      <component :is="item.icon" />
    </el-icon>
    <template #title>
      <span>{{ item.title }}</span>
    </template>
  </el-menu-item>
</template>
