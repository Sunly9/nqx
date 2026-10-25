<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  assignSysUserRoles,
  changeSysUserStatus,
  createSysUser,
  deleteSysUser,
  getSysUserRoleIds,
  listSysRoles,
  pageSysUsers,
  resetSysUserPassword,
  updateSysUser,
  type SysRoleVO,
  type SysUserQuery,
  type SysUserSaveBody,
  type SysUserVO
} from '@/api/system'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 管理员管理。
 *
 * 后端在这一块放了几条「防止把系统锁死」的规则（不能删自己、不能删/禁用超管、
 * 超管必须保留超管角色）。前端这里做的是<b>把规则提前告诉用户</b>：
 * 超管那一行干脆不显示禁用/删除按钮，免得点了才吃一个错误提示。
 * 但要注意，前端隐藏按钮只是体验优化，真正的边界在后端 —— 直接调接口一样会被拒绝。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<SysUserQuery>({
  pageNum: 1,
  pageSize: 10,
  username: '',
  realName: '',
  status: undefined
})

const loading = ref(false)
const tableData = ref<SysUserVO[]>([])
const total = ref(0)

/** 超级管理员账号 ID：与后端常量一致，用于前端提前隐藏危险按钮 */
const SUPER_ADMIN_USER_ID = 1

async function fetchList() {
  loading.value = true
  try {
    // stripEmpty 把未填写的检索项去掉：status=0（禁用）是合法值不能被误删，
    // 交给工具函数统一判断才安全
    const res = await pageSysUsers(stripEmpty(query))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[管理员管理] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.username = ''
  query.realName = ''
  query.status = undefined
  query.pageNum = 1
  fetchList()
}

function handleSizeChange(size: number) {
  query.pageSize = size
  query.pageNum = 1
  fetchList()
}

function handleCurrentChange(page: number) {
  query.pageNum = page
  fetchList()
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增管理员')
const submitting = ref(false)
const formRef = ref<FormInstance>()
/** 编辑态：账号与密码都不可改，表单要按此切换字段的展示 */
const isEdit = ref(false)

interface UserForm {
  id?: number
  username: string
  password: string
  realName: string
  phone: string
  email: string
  remark: string
}

function emptyForm(): UserForm {
  return { id: undefined, username: '', password: '', realName: '', phone: '', email: '', remark: '' }
}

const form = reactive<UserForm>(emptyForm())

/**
 * 校验规则。
 *
 * 密码只在新增时必填，因此用自定义校验函数而不是静态的 required：
 * 编辑时表单里根本没有密码输入框，静态必填会让「保存」永远失败。
 * 同时把 6 位下限也放在前端 —— 与后端 @Size(min = 6) 一致，
 * 让用户在本地就得到提示，而不是等一次请求往返。
 */
const rules: FormRules = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { min: 3, max: 50, message: '账号长度需在 3~50 位之间', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]+$/,
      message: '账号只能包含字母、数字、下划线与短横线',
      trigger: 'blur'
    }
  ],
  password: [
    {
      validator: (_rule, value: string, callback) => {
        if (isEdit.value) {
          callback()
          return
        }
        if (!value) {
          callback(new Error('请设置初始密码'))
        } else if (value.length < 6 || value.length > 64) {
          callback(new Error('密码长度需在 6~64 位之间'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

function resetForm() {
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

function handleAdd() {
  resetForm()
  isEdit.value = false
  dialogTitle.value = '新增管理员'
  dialogVisible.value = true
}

function handleEdit(row: SysUserVO) {
  resetForm()
  isEdit.value = true
  dialogTitle.value = `编辑管理员：${row.username}`
  Object.assign(form, {
    id: row.id,
    username: row.username,
    password: '',
    realName: row.realName ?? '',
    phone: row.phone ?? '',
    email: row.email ?? '',
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (form.id) {
      // 后端会忽略 username/password，这里不传更诚实：
      // 让人一眼看出「编辑资料」这个动作压根不涉及凭据
      const payload: SysUserSaveBody = {
        id: form.id,
        username: form.username,
        realName: form.realName,
        phone: form.phone || undefined,
        email: form.email || undefined,
        remark: form.remark || undefined
      }
      await updateSysUser(payload)
      ElMessage.success('修改成功')
    } else {
      const payload: SysUserSaveBody = {
        username: form.username,
        password: form.password,
        realName: form.realName,
        phone: form.phone || undefined,
        email: form.email || undefined,
        remark: form.remark || undefined
      }
      await createSysUser(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[管理员管理] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 分配角色 ------------------------------ */

const roleDialogVisible = ref(false)
const roleSubmitting = ref(false)
const roleOptions = ref<SysRoleVO[]>([])
const selectedRoleIds = ref<number[]>([])
const currentUser = ref<SysUserVO | null>(null)

async function openRoleDialog(row: SysUserVO) {
  currentUser.value = row
  selectedRoleIds.value = []
  roleDialogVisible.value = true
  try {
    // 角色下拉与已选角色并行拉取：两者互不依赖，串行只会让弹窗白等一次往返
    const [roles, roleIds] = await Promise.all([listSysRoles(), getSysUserRoleIds(row.id)])
    roleOptions.value = roles || []
    selectedRoleIds.value = roleIds || []
  } catch (error) {
    console.error('[管理员管理] 加载角色失败', error)
  }
}

async function handleAssignRoles() {
  if (!currentUser.value) return
  roleSubmitting.value = true
  try {
    await assignSysUserRoles(currentUser.value.id, selectedRoleIds.value)
    ElMessage.success('角色分配成功')
    roleDialogVisible.value = false
    // 角色的变更会立刻影响该管理员的权限，列表里的角色列也要跟着刷新
    fetchList()
  } catch (error) {
    console.error('[管理员管理] 分配角色失败', error)
  } finally {
    roleSubmitting.value = false
  }
}

/* ------------------------------ 重置密码 ------------------------------ */

const pwdDialogVisible = ref(false)
const pwdSubmitting = ref(false)
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({ password: '', confirmPassword: '' })

const pwdRules: FormRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度需在 6~64 位之间', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== pwdForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

function openPwdDialog(row: SysUserVO) {
  currentUser.value = row
  pwdForm.password = ''
  pwdForm.confirmPassword = ''
  pwdDialogVisible.value = true
  pwdFormRef.value?.clearValidate()
}

async function handleResetPassword() {
  if (!pwdFormRef.value || !currentUser.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return

  pwdSubmitting.value = true
  try {
    await resetSysUserPassword(currentUser.value.id, pwdForm.password)
    ElMessage.success('密码已重置')
    pwdDialogVisible.value = false
  } catch (error) {
    console.error('[管理员管理] 重置密码失败', error)
  } finally {
    pwdSubmitting.value = false
  }
}

/* ------------------------------ 行操作 ------------------------------ */

async function handleToggleStatus(row: SysUserVO) {
  const next = row.status === 1 ? 0 : 1
  const actionText = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      next === 0
        ? `禁用后「${row.username}」将立即被强制下线，且无法再登录，确定吗？`
        : `确定要启用「${row.username}」吗？`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await changeSysUserStatus(row.id, next)
    ElMessage.success(`${actionText}成功`)
    fetchList()
  } catch (error) {
    console.error('[管理员管理] 状态变更失败', error)
  }
}

async function handleDelete(row: SysUserVO) {
  try {
    await ElMessageBox.confirm(
      `删除后该账号将无法登录，确定要删除「${row.username}」吗？`,
      '危险操作',
      { type: 'error', confirmButtonText: '确定删除' }
    )
  } catch {
    return
  }

  try {
    await deleteSysUser(row.id)
    ElMessage.success('删除成功')
    // 删掉当前页最后一条时往前翻一页，避免停在空白页
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    fetchList()
  } catch (error) {
    console.error('[管理员管理] 删除失败', error)
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="登录账号">
          <el-input
            v-model="query.username"
            placeholder="支持模糊匹配"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="姓名">
          <el-input
            v-model="query.realName"
            placeholder="支持模糊匹配"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :icon="'Search'" @click="handleSearch">查询</el-button>
          <el-button :icon="'RefreshLeft'" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表区 -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">管理员列表（共 {{ total }} 条）</span>
        <el-button v-permission="'sys:user:add'" type="primary" :icon="'Plus'" @click="handleAdd">
          新增管理员
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="username" label="登录账号" min-width="130">
          <template #default="{ row }">
            <span>{{ row.username }}</span>
            <!-- 超管标记：让「为什么这一行没有删除按钮」变得自解释 -->
            <el-tag v-if="row.id === SUPER_ADMIN_USER_ID" type="danger" size="small" effect="plain">
              超管
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="realName" label="姓名" width="120" show-overflow-tooltip />

        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <template v-if="row.roleNames && row.roleNames.length">
              <el-tag
                v-for="name in row.roleNames"
                :key="name"
                size="small"
                effect="plain"
                class="role-tag"
              >
                {{ name }}
              </el-tag>
            </template>
            <span v-else class="text-muted">未分配</span>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="130">
          <template #default="{ row }">
            <span>{{ row.phone || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.email || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="最后登录" width="170">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.lastLoginTime) }}</div>
            <div v-if="row.lastLoginIp" class="cell-sub">{{ row.lastLoginIp }}</div>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="280" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-permission="'sys:user:edit'" link type="primary" @click="handleEdit(row)">
                编辑
              </el-button>
              <el-button
                v-permission="'sys:user:edit'"
                link
                type="primary"
                @click="openRoleDialog(row)"
              >
                分配角色
              </el-button>
              <el-button
                v-permission="'sys:user:resetPwd'"
                link
                type="warning"
                @click="openPwdDialog(row)"
              >
                重置密码
              </el-button>
              <!-- 超管账号不允许禁用：与其让用户点了再报错，不如直接不显示 -->
              <el-button
                v-if="row.id !== SUPER_ADMIN_USER_ID"
                v-permission="'sys:user:edit'"
                link
                :type="row.status === 1 ? 'warning' : 'success'"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button
                v-if="row.id !== SUPER_ADMIN_USER_ID"
                v-permission="'sys:user:delete'"
                link
                type="danger"
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无管理员数据" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="登录账号" prop="username">
          <el-input
            v-model="form.username"
            :disabled="isEdit"
            placeholder="字母、数字、下划线或短横线"
            maxlength="50"
          />
          <div v-if="isEdit" class="form-tip">账号是登录凭据，创建后不可修改</div>
        </el-form-item>

        <el-form-item v-if="!isEdit" label="初始密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 6 位"
            maxlength="64"
          />
        </el-form-item>

        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入姓名" maxlength="50" />
        </el-form-item>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="选填" maxlength="20" />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="选填" maxlength="100" />
        </el-form-item>

        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="选填"
            maxlength="500"
          />
        </el-form-item>

        <div v-if="isEdit" class="form-tip form-tip-block">
          启用/禁用请用列表中的开关按钮；修改密码请用「重置密码」——这两项都有独立的后端保护规则。
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色弹窗 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="管理员">
          <span>{{ currentUser?.username }}（{{ currentUser?.realName || '-' }}）</span>
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="selectedRoleIds"
            multiple
            clearable
            placeholder="可多选，留空表示收回全部角色"
            style="width: 100%"
          >
            <el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="role.status === 1 ? role.roleName : `${role.roleName}（已禁用）`"
              :value="role.id"
            />
          </el-select>
          <div class="form-tip">提交后该管理员的权限缓存会立即失效，新权限马上生效</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="handleAssignRoles">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="pwdDialogVisible" title="重置密码" width="440px" destroy-on-close>
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="管理员">
          <span>{{ currentUser?.username }}</span>
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="pwdDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSubmitting" @click="handleResetPassword">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-title {
  font-weight: 600;
}

.cell-sub {
  font-size: 12px;
  color: #909399;
}

.text-muted {
  color: #c0c4cc;
}

.role-tag {
  margin-right: 4px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.form-tip-block {
  padding: 0 0 0 90px;
  margin-top: -8px;
}
</style>
