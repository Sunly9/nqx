<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import Breadcrumb from './Breadcrumb.vue'

/** 顶部栏：折叠开关 + 面包屑 + 用户下拉 */
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

// 后端 AdminInfoVO 里声明了 avatar，但当前接口未回填，因此要给一个兜底显示
const avatarText = computed(() => userStore.nickname.slice(0, 1).toUpperCase())
const roleText = computed(() =>
  userStore.roles.length ? userStore.roles.join(' / ') : '普通管理员'
)

async function handleCommand(command: string) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      // 用户点了取消，ElMessageBox 会 reject，这里不是错误
      return
    }
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}
</script>

<template>
  <div class="navbar">
    <div class="navbar-left">
      <el-icon class="collapse-btn" @click="appStore.toggleSidebar()">
        <Fold v-if="!appStore.sidebarCollapsed" />
        <Expand v-else />
      </el-icon>
      <Breadcrumb />
    </div>

    <div class="navbar-right">
      <el-tag size="small" type="info" effect="plain" class="role-tag">{{ roleText }}</el-tag>

      <el-dropdown trigger="click" @command="handleCommand">
        <div class="user-info">
          <el-avatar :size="30" class="user-avatar">{{ avatarText }}</el-avatar>
          <span class="user-name">{{ userStore.nickname }}</span>
          <el-icon><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              账号：{{ userStore.userInfo?.username || '-' }}
            </el-dropdown-item>
            <el-dropdown-item divided command="logout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 16px;
  background-color: #fff;
  box-shadow: 0 1px 4px rgb(0 21 41 / 8%);
}

.navbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 100%;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #5a5e66;
}

.collapse-btn:hover {
  color: var(--nqx-primary);
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  outline: none;
}

.user-info:hover {
  background-color: #f5f7fa;
}

.user-avatar {
  background-color: var(--nqx-primary);
  color: #fff;
}

.user-name {
  font-size: 14px;
  color: #303133;
}

.role-tag {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
