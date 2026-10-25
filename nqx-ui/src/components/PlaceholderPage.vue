<script setup lang="ts">
/**
 * 占位页组件。
 *
 * 为什么需要它：动态路由要求后端每个菜单都能在前端找到对应组件文件，
 * 缺一个文件就会导致那个菜单点开报错。功能尚未开发的菜单先用本组件顶上，
 * 保证导航链路完整可用，并且页面上一眼能看出「这里还只是占位」。
 */
withDefaults(
  defineProps<{
    title: string
    /** 补充说明，例如"该模块后端接口尚未实现" */
    description?: string
  }>(),
  { description: '' }
)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="placeholder-header">
          <span class="placeholder-title">{{ title }}</span>
          <el-tag type="warning" size="small" effect="plain">功能开发中</el-tag>
        </div>
      </template>

      <el-empty :description="description || `${title}功能开发中，敬请期待`">
        <template #image>
          <el-icon class="placeholder-icon"><Tools /></el-icon>
        </template>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped>
.placeholder-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.placeholder-title {
  font-size: 16px;
  font-weight: 600;
}

.placeholder-icon {
  font-size: 72px;
  color: #c0c4cc;
}
</style>
