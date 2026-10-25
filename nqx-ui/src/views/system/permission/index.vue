<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createSysPermission,
  deleteSysPermission,
  getSysPermissionTree,
  PERM_TYPE_OPTIONS,
  updateSysPermission,
  type SysPermissionSaveBody,
  type SysPermissionVO
} from '@/api/system'

/**
 * 权限菜单管理（树形）。
 *
 * 这张表是整套 RBAC 的"权限字典"：`permCode` 被后端 @SaCheckPermission 与前端
 * v-permission 按字面量引用，`path`/`component` 决定前端动态路由。
 * 因此界面上做了两件事来降低配错概率：
 *   1. 上级节点下拉按类型过滤（目录只能挂根或目录、菜单只能挂目录、按钮只能挂菜单），
 *      把非法层级挡在选择阶段，而不是等提交后吃一个 400；
 *   2. 编辑时把自身及其子孙从上级下拉里剔除，避免把节点挂到自己下面成环。
 *
 * 权限节点只有几十个，后端一次返回整棵树，因此这里不分页也不懒加载 ——
 * 「改完刷新整棵树」比维护局部状态简单得多，也不会出现树与列表不同步的问题。
 */

const loading = ref(false)
const treeData = ref<SysPermissionVO[]>([])

async function fetchTree() {
  loading.value = true
  try {
    treeData.value = (await getSysPermissionTree()) || []
  } catch (error) {
    console.error('[权限菜单] 加载失败', error)
  } finally {
    loading.value = false
  }
}

const permTypeLabel = (type: number) =>
  PERM_TYPE_OPTIONS.find((opt) => opt.value === type)?.label ?? '未知'

const permTypeTag = (type: number) => (type === 1 ? 'primary' : type === 2 ? 'success' : 'info')

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增权限节点')
const submitting = ref(false)
const formRef = ref<FormInstance>()

interface PermissionForm {
  id?: number
  parentId: number
  permName: string
  permCode: string
  permType: number
  path: string
  component: string
  icon: string
  sort: number
  visible: number
  status: number
}

function emptyForm(): PermissionForm {
  return {
    id: undefined,
    parentId: 0,
    permName: '',
    permCode: '',
    permType: 1,
    path: '',
    component: '',
    icon: '',
    sort: 0,
    visible: 1,
    status: 1
  }
}

const form = reactive<PermissionForm>(emptyForm())

/**
 * 类型相关的必填规则。
 *
 * 后端对「菜单必须填 permCode/path/component」「按钮必须填 permCode」做了强校验，
 * 前端同步一份是为了让用户在本地就看到红字提示，而不是提交后才知道。
 * 目录只用于分组，这些字段全部可以留空。
 */
const rules: FormRules = {
  parentId: [{ required: true, message: '请选择上级节点', trigger: 'change' }],
  permName: [{ required: true, message: '请输入权限名称', trigger: 'blur' }],
  permType: [{ required: true, message: '请选择权限类型', trigger: 'change' }],
  permCode: [
    {
      validator: (_rule, value: string, callback) => {
        if (form.permType !== 1 && !value) {
          callback(new Error('菜单与按钮必须填写权限标识'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  path: [
    {
      validator: (_rule, value: string, callback) => {
        if (form.permType === 2 && !value) {
          callback(new Error('菜单必须填写前端路由地址'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  component: [
    {
      validator: (_rule, value: string, callback) => {
        if (form.permType === 2 && !value) {
          callback(new Error('菜单必须填写前端组件路径'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/** 收集某节点的全部子孙 ID（含自身），用于把它从上级候选中剔除 */
function collectSelfAndDescendantIds(node: SysPermissionVO, acc: Set<number> = new Set()): Set<number> {
  acc.add(node.id)
  for (const child of node.children ?? []) {
    collectSelfAndDescendantIds(child, acc)
  }
  return acc
}

function findNode(nodes: SysPermissionVO[], id?: number): SysPermissionVO | undefined {
  if (id === undefined) return undefined
  for (const node of nodes) {
    if (node.id === id) return node
    const found = findNode(node.children ?? [], id)
    if (found) return found
  }
  return undefined
}

/** 挑出指定类型的节点；同类型的父节点保留为容器，以便展示嵌套结构 */
function filterByType(nodes: SysPermissionVO[], wantType: number): SysPermissionVO[] {
  const result: SysPermissionVO[] = []
  for (const node of nodes) {
    if (node.permType === wantType) {
      const children = node.children ? filterByType(node.children, wantType) : []
      result.push({ ...node, children: children.length ? children : undefined })
    } else if (node.children) {
      // 类型不匹配但子树里可能有目标类型（例如 目录 → 目录 → 菜单）
      result.push(...filterByType(node.children, wantType))
    }
  }
  return result
}

/**
 * 上级节点候选（带一个 id=0 的虚拟根）。
 *
 * 两项过滤缺一不可：
 *   - 按类型过滤：菜单不能挂在菜单下、按钮不能挂在目录下；
 *   - 剔除自身与子孙：否则会形成环，那棵子树会从树上消失却仍然参与鉴权
 *     （后端也会拦，但界面不该把这选项摆出来）。
 * 虚拟根只对「目录」开放：根节点下只能放目录。
 */
const parentTreeData = computed(() => {
  const self = form.id ? findNode(treeData.value, form.id) ?? ({ id: form.id } as SysPermissionVO) : undefined
  const excluded = self ? collectSelfAndDescendantIds(self) : new Set<number>()

  const filterExcluded = (nodes: SysPermissionVO[]): SysPermissionVO[] =>
    nodes
      .filter((node) => !excluded.has(node.id))
      .map((node) => {
        const children = node.children ? filterExcluded(node.children) : []
        return { ...node, children: children.length ? children : undefined }
      })

  const available = filterExcluded(treeData.value)
  // 菜单只能挂在目录下，因此它只关心目录；编辑目录时也只允许在目录之间移动
  const candidates = form.permType === 3 ? filterByType(available, 2) : filterByType(available, 1)
  return [{ id: 0, parentId: 0, permName: '根节点', permType: 1, children: candidates }]
})

function resetForm() {
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

function handleAdd(parent?: SysPermissionVO) {
  resetForm()
  if (parent) {
    form.parentId = parent.id
    // 目录下只能建菜单、菜单下只能建按钮：这是后端强制的层级约束，
    // 默认值直接给出符合约束的选项，用户少一次选择
    form.permType = parent.permType === 1 ? 2 : 3
  }
  dialogTitle.value = parent ? `在「${parent.permName}」下新增子节点` : '新增一级目录'
  dialogVisible.value = true
}

function handleEdit(row: SysPermissionVO) {
  resetForm()
  dialogTitle.value = `编辑权限：${row.permName}`
  Object.assign(form, {
    id: row.id,
    parentId: row.parentId,
    permName: row.permName,
    permCode: row.permCode ?? '',
    permType: row.permType,
    path: row.path ?? '',
    component: row.component ?? '',
    icon: row.icon ?? '',
    sort: row.sort ?? 0,
    visible: row.visible ?? 1,
    status: row.status ?? 1
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 目录用不到的字段一律传 undefined，避免把上一轮编辑的残留值写进库
    const payload: SysPermissionSaveBody = {
      id: form.id,
      parentId: form.parentId,
      permName: form.permName,
      permCode: form.permType === 1 ? undefined : form.permCode,
      permType: form.permType,
      path: form.permType === 2 ? form.path : undefined,
      component: form.permType === 2 ? form.component : undefined,
      icon: form.icon || undefined,
      sort: form.sort,
      visible: form.visible,
      status: form.status
    }
    if (form.id) {
      await updateSysPermission(payload)
      ElMessage.success('修改成功')
    } else {
      await createSysPermission(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchTree()
  } catch (error) {
    console.error('[权限菜单] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: SysPermissionVO) {
  const hasChildren = !!row.children?.length
  try {
    // 后端会拒绝删除有子节点的权限，这里提前说明原因，免得用户以为是自己点错了
    await ElMessageBox.confirm(
      hasChildren
        ? `「${row.permName}」下还有 ${row.children!.length} 个子节点，必须先删除子节点。确定继续吗？`
        : `删除后该权限会从所有角色的授权中移除，并可能立刻影响已登录管理员的权限，确定删除「${row.permName}」吗？`,
      '危险操作',
      { type: 'error', confirmButtonText: '确定删除' }
    )
  } catch {
    return
  }

  try {
    await deleteSysPermission(row.id)
    ElMessage.success('删除成功')
    fetchTree()
  } catch (error) {
    console.error('[权限菜单] 删除失败', error)
  }
}

onMounted(fetchTree)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">
          权限菜单（目录 / 菜单 / 按钮，共 {{ treeData.length }} 个一级节点）
        </span>
        <div>
          <el-button :icon="'Refresh'" @click="fetchTree">刷新</el-button>
          <el-button v-permission="'sys:perm:add'" type="primary" :icon="'Plus'" @click="handleAdd()">
            新增一级目录
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="treeData"
        row-key="id"
        border
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="permName" label="权限名称" min-width="180" />

        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="permTypeTag(row.permType)" size="small" effect="plain">
              {{ permTypeLabel(row.permType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="permCode" label="权限标识" min-width="180">
          <template #default="{ row }">
            <span v-if="row.permCode" class="mono">{{ row.permCode }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="path" label="路由地址" min-width="140">
          <template #default="{ row }">
            <span>{{ row.path || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="component" label="组件路径" min-width="170">
          <template #default="{ row }">
            <span>{{ row.component || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="图标" width="130">
          <template #default="{ row }">
            <span v-if="row.icon" class="icon-cell">
              <el-icon><component :is="row.icon" /></el-icon>
              <span class="cell-sub">{{ row.icon }}</span>
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="sort" label="排序" width="70" align="center" />

        <el-table-column label="显示" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.visible === 1 ? 'success' : 'info'" size="small">
              {{ row.visible === 1 ? '显示' : '隐藏' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <!-- 按钮是树的最底层，不可能再有子节点，因此只对目录/菜单显示 -->
              <el-button
                v-if="row.permType !== 3"
                v-permission="'sys:perm:add'"
                link
                type="primary"
                @click="handleAdd(row)"
              >
                新增子节点
              </el-button>
              <el-button v-permission="'sys:perm:edit'" link type="primary" @click="handleEdit(row)">
                编辑
              </el-button>
              <el-button
                v-permission="'sys:perm:delete'"
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
          <el-empty description="暂无权限节点" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="上级节点" prop="parentId">
          <el-tree-select
            v-model="form.parentId"
            :data="parentTreeData"
            :props="{ label: 'permName', children: 'children' }"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            placeholder="请选择上级节点"
            style="width: 100%"
          />
          <div class="form-tip">目录可挂根节点，菜单挂在目录下，按钮挂在菜单下</div>
        </el-form-item>

        <el-form-item label="权限类型" prop="permType">
          <el-radio-group v-model="form.permType" :disabled="!!form.id">
            <el-radio v-for="opt in PERM_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio>
          </el-radio-group>
          <div v-if="form.id" class="form-tip">有子节点的节点不允许修改类型，需要改请先删除子节点</div>
        </el-form-item>

        <el-form-item label="权限名称" prop="permName">
          <el-input v-model="form.permName" placeholder="如 管理员管理" maxlength="50" />
        </el-form-item>

        <el-form-item v-if="form.permType !== 1" label="权限标识" prop="permCode">
          <el-input v-model="form.permCode" placeholder="如 sys:user:list" maxlength="100" />
          <div class="form-tip">
            这个字符串被后端 @SaCheckPermission 与前端 v-permission 直接引用，改动会让已授权角色失去该权限
          </div>
        </el-form-item>

        <template v-if="form.permType === 2">
          <el-form-item label="路由地址" prop="path">
            <el-input v-model="form.path" placeholder="如 /system/user" maxlength="200" />
          </el-form-item>

          <el-form-item label="组件路径" prop="component">
            <el-input
              v-model="form.component"
              placeholder="如 system/user/index（相对 src/views，不带 .vue）"
              maxlength="200"
            />
          </el-form-item>
        </template>

        <el-form-item label="图标" prop="icon">
          <el-input
            v-model="form.icon"
            placeholder="选填，Element Plus 图标名，如 Setting"
            maxlength="100"
          />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" style="width: 160px" />
          <div class="form-tip">数字越小越靠前</div>
        </el-form-item>

        <el-form-item label="是否显示" prop="visible">
          <el-radio-group v-model="form.visible">
            <el-radio :value="1">显示</el-radio>
            <el-radio :value="0">隐藏</el-radio>
          </el-radio-group>
          <div class="form-tip">隐藏的节点不显示在侧边栏，但路由与权限仍然生效</div>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
          <div class="form-tip">禁用后该权限码不再参与鉴权计算</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
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

.cell-sub {
  font-size: 12px;
  color: #909399;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.icon-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
