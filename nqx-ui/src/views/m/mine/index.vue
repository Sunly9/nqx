<template>
  <div class="m-page">
    <!-- ================= 账号信息 ================= -->
    <section class="hero">
      <div class="hero__avatar">{{ avatarText }}</div>
      <div class="hero__info">
        <div class="hero__name">{{ userStore.nickname }}</div>
        <div class="hero__role">{{ roleText }}</div>
      </div>
    </section>

    <!-- ================= 权限概览 ================= -->
    <section class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">我的权限</span>
        <span class="muted">{{ permissionCount }} 项</span>
      </div>
      <div v-if="userStore.isSuperAdmin" class="m-tag m-tag--success">超级管理员（拥有全部权限）</div>
      <div v-else-if="permissionCount === 0" class="muted">当前账号没有任何权限码</div>
      <div v-else class="perms">
        <span v-for="p in userStore.permissions.slice(0, 12)" :key="p" class="perm">{{ p }}</span>
        <span v-if="permissionCount > 12" class="muted">等 {{ permissionCount }} 项</span>
      </div>
    </section>

    <!-- ================= 移动端说明 ================= -->
    <section class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">关于手机版</span>
      </div>
      <p class="note">
        手机版只保留日常运营最高频的操作：<strong>看数据、审回收、发货、商品上下架</strong>。
      </p>
      <p class="note">
        权限配置、角色管理、题库编辑、系统日志这些低频且复杂的配置，
        手机上操作容易出错，请到电脑版处理。
      </p>
    </section>

    <!-- ================= 操作 ================= -->
    <section class="m-card">
      <button class="cell" @click="openDesktop">
        <span class="cell__label">切换到电脑版</span>
        <span class="cell__arrow">›</span>
      </button>
      <button class="cell cell--danger" @click="logout">
        <span class="cell__label">退出登录</span>
        <span class="cell__arrow">›</span>
      </button>
    </section>

    <p class="footnote">纽启新生 · 管理后台 手机版 v1.0</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { setPreferDesktop } from '@/utils/device'

/**
 * 我的。
 *
 * 这一页承担一个重要的职责：**明确告诉用户哪些功能不在手机上做**。
 *
 * 如果只是默默地把功能藏起来，用户会以为"手机版是残缺的"。
 * 说清楚"这几个功能在电脑上做，因为手机屏幕放不下"，
 * 反而显得是有意为之的设计，而不是能力不足。
 */

const router = useRouter()
const userStore = useUserStore()

const avatarText = computed(() => (userStore.nickname || '?').slice(0, 1))

const permissionCount = computed(() => userStore.permissions.length)

const roleText = computed(() => {
  const roles = userStore.roles
  if (userStore.isSuperAdmin) return '超级管理员'
  return roles.length ? roles.join(' / ') : '管理员'
})

function openDesktop() {
  setPreferDesktop()
  router.replace('/dashboard/index')
}

async function logout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    // 用户点了取消
    return
  }

  await userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 16px;
  background: linear-gradient(135deg, #409eff, #6fb4ff);
  color: #fff;
}

.hero__avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.25);
}

.hero__name {
  font-size: 17px;
  font-weight: 600;
}

.hero__role {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.85;
}

.muted {
  font-size: 12px;
  color: var(--m-text-3);
}

.perms {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.perm {
  padding: 2px 8px;
  border-radius: 4px;
  background: #f2f3f5;
  font-size: 11px;
  color: var(--m-text-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.note {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--m-text-2);
}

.note:last-child {
  margin-bottom: 0;
}

.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  /* 44px 是可点区域下限 */
  min-height: 44px;
  padding: 12px 0;
  border: none;
  border-bottom: 1px solid var(--m-line);
  background: transparent;
  font-size: 15px;
  color: var(--m-text);
  cursor: pointer;
}

.cell:last-child {
  border-bottom: none;
}

.cell--danger .cell__label {
  color: var(--m-danger);
}

.cell__arrow {
  color: var(--m-text-3);
  font-size: 18px;
  line-height: 1;
}

.footnote {
  margin: 8px 0 24px;
  text-align: center;
  font-size: 11px;
  color: var(--m-text-3);
}
</style>
