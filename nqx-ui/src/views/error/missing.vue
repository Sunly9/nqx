<script setup lang="ts">
import { useRoute } from 'vue-router'

/**
 * 组件缺失兜底页。
 *
 * 动态路由里若某个菜单的 component 路径在 src/views 下找不到对应 .vue，
 * buildRoutes 会退回到本组件。这样单个菜单配置错误不会导致整个后台白屏，
 * 具体是哪个菜单出错可以看控制台日志。
 */
const route = useRoute()
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-alert
        type="error"
        :closable="false"
        title="页面组件缺失"
        :description="`路径 ${route.path} 对应的 .vue 文件不存在，请在 src/views 下补建，或检查后端菜单的 component 配置。`"
        show-icon
      />
    </el-card>
  </div>
</template>
