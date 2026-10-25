<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 登录页。
 *
 * 登录成功后只做一件事：把 token 落盘。
 * 菜单与动态路由不在这里注册，而是交给路由守卫按需加载 ——
 * 这样「登录后进入」和「刷新页面」走的是同一条代码路径，
 * 不会出现两条分支各写一遍、其中一条漏改的情况。
 */
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

// 页面上给出默认账号提示，方便验收环境直接登录
const form = reactive({
  username: 'admin',
  password: 'Admin@123'
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ]
}

async function handleLogin() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    ElMessage.success('登录成功')

    // 回跳到被拦截前的目标地址，没有则进首页
    const redirect = (route.query.redirect as string) || '/'
    await router.replace(redirect)
  } catch (error) {
    // 失败提示已由 axios 拦截器统一弹出，这里只负责不跳转
    console.error('[登录] 失败', error)
  } finally {
    loading.value = false
  }
}

/** 回车即提交，省掉一次鼠标移动 */
function handleEnter() {
  handleLogin()
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <img src="/logo.svg" alt="logo" class="login-logo" />
        <h1 class="login-title">纽启新生</h1>
        <p class="login-subtitle">电池回收管理后台</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
        @keyup.enter="handleEnter"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable>
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            clearable
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            {{ loading ? '登录中…' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert type="info" :closable="false" class="login-tip">
        <template #title>
          演示账号：<b>admin</b> / <b>Admin@123</b>
        </template>
      </el-alert>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #1f3a5f 0%, #2d6cb5 50%, #409eff 100%);
}

.login-card {
  width: 400px;
  padding: 36px 36px 24px;
  background-color: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgb(0 0 0 / 20%);
}

.login-brand {
  text-align: center;
  margin-bottom: 24px;
}

.login-logo {
  width: 52px;
  height: 52px;
}

.login-title {
  margin: 12px 0 4px;
  font-size: 22px;
  letter-spacing: 2px;
  color: #1f3a5f;
}

.login-subtitle {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.login-btn {
  width: 100%;
  letter-spacing: 4px;
}

.login-tip {
  margin-top: 4px;
}
</style>
