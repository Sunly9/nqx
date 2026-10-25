<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, ElTree, type FormInstance, type FormRules } from 'element-plus'
import {
  assignSysRolePermissions,
  createSysRole,
  deleteSysRole,
  getSysPermissionTree,
  getSysRolePermissionIds,
  pageSysRoles,
  updateSysRole,
  type SysPermissionVO,
  type SysRoleQuery,
  type SysRoleSaveBody,
  type SysRoleVO
} from '@/api/system'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 角色管理。
 *
 * 「分配权限」是本页的重点，这里有一个 el-tree 勾选组件的经典坑：
 * 默认（父子联动）模式下，如果把后端返回的全部权限 ID 都塞给 setCheckedKeys，
 * 那么只要角色拥有某个目录，目录下的所有子节点都会被**自动勾选**，
 * 于是"打开弹窗什么都没改，一保存权限就变多了"。
 *
 * 处理办法是把「存」与「取」分开：
 *   - 回填时只设置叶子节点（父节点由 el-tree 自己算成半选）；
 *   - 提交时把「全选节点 + 半选节点」一起提交，
 *     这样父级目录/菜单的 ID 也会入库 —— 否则侧边栏会因为缺少父目录而丢层级。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<SysRoleQuery>({
  pageNum: 1,
  pageSize: 10,
  roleCode: '',
  roleName: '',
  status: undefined
})

const loading = ref(false)
const tableData = ref<SysRoleVO[]>([])
const total = ref(0)

/** 超级管理员角色 ID：与后端常量一致，用于前端提前隐藏删除按钮 */
const SUPER_ADMIN_ROLE_ID = 1

async function fetchList() {
  loading.value = true
  try {
    const res = await pageSysRoles(stripEmpty(query))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[角色管理] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.roleCode = ''
  query.roleName = ''
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
const dialogTitle = ref('新增角色')
const submitting = ref(false)
const formRef = ref<FormInstance>()

interface RoleForm {
  id?: number
  roleCode: string
  roleName: string
  description: string
  sort: number
  status: number
}

function emptyForm(): RoleForm {
  return { id: undefined, roleCode: '', roleName: '', description: '', sort: 0, status: 1 }
}

const form = reactive<RoleForm>(emptyForm())

const rules: FormRules = {
  roleCode: [
    { required: true, message: '请输入角色标识', trigger: 'blur' },
    {
      // 与后端 @Pattern 保持一致：标识会被写进会话并按字面量比较，
      // 大小写不一致会让鉴权静默失败，因此在入口就收紧格式
      pattern: /^[A-Z][A-Z0-9_]*$/,
      message: '只能包含大写字母、数字与下划线，且以字母开头',
      trigger: 'blur'
    }
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}

function resetForm() {
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

function handleAdd() {
  resetForm()
  dialogTitle.value = '新增角色'
  dialogVisible.value = true
}

function handleEdit(row: SysRoleVO) {
  resetForm()
  dialogTitle.value = `编辑角色：${row.roleName}`
  Object.assign(form, {
    id: row.id,
    roleCode: row.roleCode,
    roleName: row.roleName,
    description: row.description ?? '',
    sort: row.sort ?? 0,
    status: row.status
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload: SysRoleSaveBody = { ...form }
    if (form.id) {
      await updateSysRole(payload)
      ElMessage.success('修改成功')
    } else {
      await createSysRole(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[角色管理] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: SysRoleVO) {
  const inUse = (row.userCount ?? 0) > 0
  try {
    await ElMessageBox.confirm(
      inUse
        ? `该角色下还有 ${row.userCount} 个管理员，需要先解除关联才能删除。确定继续吗？`
        : `删除后该角色的授权记录会一并清除，确定要删除「${row.roleName}」吗？`,
      '危险操作',
      { type: 'error', confirmButtonText: '确定删除' }
    )
  } catch {
    return
  }

  try {
    await deleteSysRole(row.id)
    ElMessage.success('删除成功')
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    fetchList()
  } catch (error) {
    console.error('[角色管理] 删除失败', error)
  }
}

/* ------------------------------ 分配权限 ------------------------------ */

const permDialogVisible = ref(false)
const permSubmitting = ref(false)
const permissionTree = ref<SysPermissionVO[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()
const currentRole = ref<SysRoleVO | null>(null)

/** 有子节点的节点 ID 集合：用于从"已存权限"里筛出叶子节点 */
function collectParentIds(nodes: SysPermissionVO[], acc: Set<number> = new Set()): Set<number> {
  for (const node of nodes) {
    if (node.children && node.children.length > 0) {
      acc.add(node.id)
      collectParentIds(node.children, acc)
    }
  }
  return acc
}

async function openPermDialog(row: SysRoleVO) {
  currentRole.value = row
  permDialogVisible.value = true
  try {
    // 权限树与已勾选权限并行拉取，减少弹窗的空白等待
    const [tree, checkedIds] = await Promise.all([getSysPermissionTree(), getSysRolePermissionIds(row.id)])
    permissionTree.value = tree || []
    // 等树渲染完成后再设置勾选，否则 treeRef 还是空的（首次打开时尤其明显）
    await nextTick()
    const parentIds = collectParentIds(permissionTree.value)
    treeRef.value?.setCheckedKeys((checkedIds || []).filter((id) => !parentIds.has(id)))
  } catch (error) {
    console.error('[角色管理] 加载权限树失败', error)
  }
}

async function handleAssignPermissions() {
  if (!currentRole.value || !treeRef.value) return
  permSubmitting.value = true
  try {
    // 半选的父节点也要提交：只提交叶子会让侧边栏缺少目录层级
    const checked = treeRef.value.getCheckedKeys() as number[]
    const halfChecked = treeRef.value.getHalfCheckedKeys() as number[]
    await assignSysRolePermissions(currentRole.value.id, [...checked, ...halfChecked])
    ElMessage.success('权限分配成功')
    permDialogVisible.value = false
  } catch (error) {
    console.error('[角色管理] 分配权限失败', error)
  } finally {
    permSubmitting.value = false
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="角色标识">
          <el-input
            v-model="query.roleCode"
            placeholder="如 SUPER"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="角色名称">
          <el-input
            v-model="query.roleName"
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
        <span class="table-title">角色列表（共 {{ total }} 条）</span>
        <el-button v-permission="'sys:role:add'" type="primary" :icon="'Plus'" @click="handleAdd">
          新增角色
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="roleCode" label="角色标识" min-width="140">
          <template #default="{ row }">
            <span>{{ row.roleCode }}</span>
            <el-tag v-if="row.id === SUPER_ADMIN_ROLE_ID" type="danger" size="small" effect="plain">
              超管
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="roleName" label="角色名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />

        <el-table-column label="关联管理员" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userCount" type="warning" size="small" effect="plain">
              {{ row.userCount }} 人
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="sort" label="排序" width="80" align="center" />

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-permission="'sys:role:edit'" link type="primary" @click="handleEdit(row)">
                编辑
              </el-button>
              <el-button
                v-permission="'sys:role:assign'"
                link
                type="primary"
                @click="openPermDialog(row)"
              >
                分配权限
              </el-button>
              <el-button
                v-if="row.id !== SUPER_ADMIN_ROLE_ID"
                v-permission="'sys:role:delete'"
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
          <el-empty description="暂无角色数据" :image-size="80" />
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
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="角色标识" prop="roleCode">
          <!-- 超管角色的标识不允许改：hasRole('SUPER_ADMIN') 这类判断是按字面量写的 -->
          <el-input
            v-model="form.roleCode"
            :disabled="form.id === SUPER_ADMIN_ROLE_ID"
            placeholder="如 OPERATOR，全大写"
            maxlength="50"
          />
          <div v-if="form.id === SUPER_ADMIN_ROLE_ID" class="form-tip">
            超级管理员角色的标识不允许修改
          </div>
        </el-form-item>

        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="请输入角色名称" maxlength="50" />
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="选填，说明这个角色的职责范围"
            maxlength="200"
          />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
          <div class="form-tip">数字越小越靠前</div>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status" :disabled="form.id === SUPER_ADMIN_ROLE_ID">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
          <div v-if="form.id === SUPER_ADMIN_ROLE_ID" class="form-tip">
            超级管理员角色不允许禁用
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配权限弹窗 -->
    <el-dialog
      v-model="permDialogVisible"
      :title="`分配权限：${currentRole?.roleName ?? ''}`"
      width="560px"
      destroy-on-close
    >
      <div class="tree-toolbar">
        <span class="form-tip">勾选父节点会连带勾选其下所有子节点</span>
        <div>
          <el-button link type="primary" @click="treeRef?.setCheckedKeys([])">全不选</el-button>
        </div>
      </div>

      <div class="tree-wrapper">
        <el-tree
          v-if="permissionTree.length"
          ref="treeRef"
          :data="permissionTree"
          show-checkbox
          node-key="id"
          default-expand-all
          :props="{ label: 'permName', children: 'children' }"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span>{{ data.permName }}</span>
              <el-tag size="small" effect="plain" :type="data.permType === 3 ? 'info' : 'primary'">
                {{ data.permType === 1 ? '目录' : data.permType === 2 ? '菜单' : '按钮' }}
              </el-tag>
              <span v-if="data.permCode" class="tree-code">{{ data.permCode }}</span>
            </span>
          </template>
        </el-tree>
        <el-empty v-else description="暂无权限节点" :image-size="80" />
      </div>

      <template #footer>
        <el-button @click="permDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="permSubmitting" @click="handleAssignPermissions">
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

.text-muted {
  color: #c0c4cc;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

/* 权限树可能很长，限高滚动，避免弹窗把页脚按钮顶出屏幕 */
.tree-wrapper {
  max-height: 420px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
}

.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tree-code {
  font-size: 12px;
  color: #909399;
}
</style>
