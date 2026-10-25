<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 多标签页导航（可选功能，这里做了一版不带 keep-alive 的实现）。
 *
 * 为什么不做 keep-alive 缓存？
 * KeepAlive 的 include 要按「组件 name」匹配，而本项目的视图文件名大多是 index.vue，
 * 在 <script setup> 下自动推断出的 name 全是 "index"，无法区分。
 * 要缓存就得给每个视图手写 defineOptions({ name })，
 * 而这属于产品体验优化、不影响功能可用，因此先留白。
 */
interface TagView {
  path: string
  title: string
}

const route = useRoute()
const router = useRouter()

const visited = ref<TagView[]>([])

// 跟随路由自动累积标签；首页（看板）恒定为第一个标签，不可关闭
watch(
  () => route.path,
  () => {
    if (!route.meta?.title) return
    if (visited.value.some((tag) => tag.path === route.path)) return
    visited.value.push({ path: route.path, title: route.meta.title })
  },
  { immediate: true }
)

function isActive(tag: TagView) {
  return tag.path === route.path
}

function closeTag(tag: TagView) {
  const index = visited.value.findIndex((item) => item.path === tag.path)
  if (index === -1) return
  visited.value.splice(index, 1)

  // 关掉的是当前页时，跳到相邻的一个标签；一个都不剩就回首页
  if (isActive(tag)) {
    const next = visited.value[index] || visited.value[index - 1] || visited.value[0]
    router.push(next ? next.path : '/')
  }
}

const canClose = computed(() => visited.value.length > 1)
</script>

<template>
  <div class="tags-view">
    <el-tag
      v-for="tag in visited"
      :key="tag.path"
      :effect="isActive(tag) ? 'dark' : 'plain'"
      :closable="canClose"
      class="tag-item"
      size="default"
      @click="router.push(tag.path)"
      @close="closeTag(tag)"
    >
      {{ tag.title }}
    </el-tag>
  </div>
</template>

<style scoped>
.tags-view {
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--nqx-tags-height);
  padding: 0 16px;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  overflow-x: auto;
  white-space: nowrap;
}

.tag-item {
  cursor: pointer;
  user-select: none;
}
</style>
