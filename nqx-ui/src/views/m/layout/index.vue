<template>
  <div class="m-shell">
    <!--
      顶部导航。
      手机上不使用侧边栏 —— 210px 的侧边栏在 375px 宽的屏幕上会吃掉一半以上空间。
    -->
    <header class="m-nav">
      <button
        v-if="showBack"
        class="m-nav__btn m-nav__btn--back"
        aria-label="返回"
        @click="goBack"
      >
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M15 19l-7-7 7-7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <span v-else class="m-nav__spacer" />

      <h1 class="m-nav__title">{{ pageTitle }}</h1>

      <button class="m-nav__btn" aria-label="切换到电脑版" @click="openDesktop">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8">
          <rect x="2.5" y="4" width="19" height="12.5" rx="1.6" />
          <path d="M8 20h8M12 16.5V20" stroke-linecap="round" />
        </svg>
      </button>
    </header>

    <main class="m-body">
      <router-view />
    </main>

    <!--
      底部 Tab 只在「Tab 根页面」显示。

      子页面（比如商品编辑）有自己的固定底部操作栏，
      两个都显示会叠在一起，而且子页面本来就该用返回按钮回上一级 ——
      这是移动端 App 的通用形态。
    -->
    <nav v-if="isTabRoot" class="m-tabbar">
      <router-link
        v-for="tab in tabs"
        :key="tab.path"
        :to="tab.path"
        class="m-tab"
        :class="{ 'm-tab--active': activeTab === tab.key }"
        replace
      >
        <span class="m-tab__icon" v-html="tab.icon" />
        <span class="m-tab__label">{{ tab.label }}</span>
      </router-link>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { setPreferDesktop } from '@/utils/device'

/**
 * 移动端外壳。
 *
 * <h2>为什么单独一套布局</h2>
 *
 * 桌面端的 `layout/index.vue` 是「侧边栏 + 顶栏 + 多标签页」，所有尺寸都是按
 * 宽屏写死的。在手机上硬套只有两个结果：要么侧边栏吃掉大半个屏幕，
 * 要么所有东西挤成一团。
 *
 * 商家类 App（淘宝商家版、抖音商家版）的通用形态是：
 *   顶部一条标题栏（带返回），中间内容单列纵向滚动，底部固定 4 个 Tab。
 * 这套布局就是照这个形态做的。
 */

const route = useRoute()
const router = useRouter()

/** 底部 Tab：4 个是移动端的实际上限，再多就点不准了 */
const tabs = [
  {
    key: 'home',
    label: '工作台',
    path: '/m/home',
    // 内联 SVG 而不是字体图标：不额外引依赖，且不会被各机型字体差异影响
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3.5 10.5 12 3.5l8.5 7" stroke-linecap="round" stroke-linejoin="round"/><path d="M5.5 9.5V20h13V9.5" stroke-linecap="round"/><path d="M9.5 20v-5.5h5V20" stroke-linejoin="round"/></svg>'
  },
  {
    key: 'product',
    label: '商品',
    path: '/m/product',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3 21 7.5v9L12 21 3 16.5v-9L12 3Z" stroke-linejoin="round"/><path d="M3 7.5 12 12l9-4.5M12 12v9" stroke-linejoin="round"/></svg>'
  },
  {
    key: 'orders',
    label: '订单',
    path: '/m/orders',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="3.5" width="16" height="17" rx="2"/><path d="M8 8.5h8M8 12h8M8 15.5h5" stroke-linecap="round"/></svg>'
  },
  {
    key: 'mine',
    label: '我的',
    path: '/m/mine',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="8" r="4"/><path d="M4.5 20c0-3.6 3.4-6 7.5-6s7.5 2.4 7.5 6" stroke-linecap="round"/></svg>'
  }
] as const

/**
 * 当前高亮的 Tab。
 *
 * 不能直接用 `route.path` 比较：编辑页 `/m/product/edit/12` 属于商品模块，
 * 但路径和 `/m/product` 不相等 —— 用 `startsWith` 让它保持"商品"高亮。
 */
const activeTab = computed<string>(() => {
  const path = route.path
  if (path.startsWith('/m/product')) return 'product'
  if (path.startsWith('/m/orders')) return 'orders'
  if (path.startsWith('/m/mine')) return 'mine'
  return 'home'
})

/** 是不是 Tab 根页面（决定要不要显示返回箭头） */
const isTabRoot = computed(() => Boolean(route.meta.tab))

const showBack = computed(() => !isTabRoot.value)

const pageTitle = computed(() => {
  const title = route.meta.title
  return typeof title === 'string' ? title : '纽启新生'
})

function goBack() {
  // 直接打开深链接时没有上一页，退不回去就回工作台，避免白屏
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace('/m/home')
  }
}

/**
 * 切回电脑版。
 *
 * 记一个偏好标记，否则下次打开又会被自动判定成移动端跳回来 ——
 * 用户点这一下就是明确的"我要用电脑版"的意思，应该被尊重。
 */
function openDesktop() {
  setPreferDesktop()
  router.replace('/dashboard/index')
}
</script>

<style>
/* 公共样式只在进入移动端时加载，不拖累桌面端首屏 */
@import '@/styles/mobile.css';
</style>

<style scoped>
.m-shell {
  min-height: 100vh;
  background: var(--m-bg);
}

/* -------------------------------------------------------------------------
 * 顶部导航
 *
 * 用 position: fixed 而不是 sticky：部分安卓浏览器在 sticky + 滚动容器
 * 的组合下会出现"顶栏跟着内容一起滚走"的问题。
 * ----------------------------------------------------------------------- */
.m-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 6px;
  background: #fff;
  border-bottom: 1px solid var(--m-line);
}

.m-nav__title {
  flex: 1;
  margin: 0;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: var(--m-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.m-nav__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  /* 44px 是移动端可点区域的下限，小于这个值容易点不中 */
  width: 44px;
  height: 44px;
  border: none;
  background: transparent;
  color: var(--m-text-2);
  cursor: pointer;
}

.m-nav__btn--back {
  color: var(--m-text);
}

.m-nav__spacer {
  width: 44px;
}

/* -------------------------------------------------------------------------
 * 内容区
 * ----------------------------------------------------------------------- */
.m-body {
  padding-top: 48px;
}

/* -------------------------------------------------------------------------
 * 底部 Tab
 * ----------------------------------------------------------------------- */
.m-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  height: calc(var(--m-tabbar-h) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;
  border-top: 1px solid var(--m-line);
}

.m-tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--m-text-3);
  text-decoration: none;
  font-size: 11px;
}

.m-tab--active {
  color: var(--m-primary);
}

.m-tab__icon {
  display: block;
  width: 22px;
  height: 22px;
}

.m-tab__icon :deep(svg) {
  width: 22px;
  height: 22px;
}
</style>
