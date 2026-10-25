<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createCategory,
  deleteCategory,
  getCategoryTree,
  updateCategory,
  type CategorySaveBody
} from '@/api/product'
import type { CategoryVO } from '@/types/product'

/**
 * 商品分类管理（树形）。
 *
 * 数据来自 /admin/product/category/tree，后端一次返回整棵树，
 * 因此不需要分页，也不需要懒加载 —— 分类量级很小（几十个），
 * 一次取全反而让「新建后刷新整棵树」这种操作变得无比简单。
 */

const loading = ref(false)
const treeData = ref<CategoryVO[]>([])

async function fetchTree() {
  loading.value = true
  try {
    treeData.value = await getCategoryTree()
  } catch (error) {
    console.error('[分类管理] 加载失败', error)
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增分类')
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<CategorySaveBody>({
  id: undefined,
  // 0 代表根分类。后端对 parentId 是 @NotNull，绝不能留空
  parentId: 0,
  name: '',
  icon: '',
  sort: 0,
  status: 1
})

const rules: FormRules = {
  parentId: [{ required: true, message: '请选择上级分类', trigger: 'change' }],
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 50, message: '分类名称不能超过 50 个字符', trigger: 'blur' }
  ]
}

/**
 * 上级分类下拉的数据源。
 *
 * 后端约定 parentId=0 表示根分类，而下拉的选项必须是「真实的树」，
 * 因此手工拼一个 id=0 的虚拟根节点套在最外层 ——
 * 这样用户可以直接选「顶级分类」，语义与后端完全对齐。
 */
const parentTreeData = computed(() => [
  { id: 0, name: '顶级分类', parentId: 0, children: treeData.value }
])

function handleAdd(parent?: CategoryVO) {
  Object.assign(form, {
    id: undefined,
    parentId: parent ? parent.id : 0,
    name: '',
    icon: '',
    sort: 0,
    status: 1
  })
  dialogTitle.value = parent ? `在「${parent.name}」下新增子分类` : '新增分类'
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function handleEdit(row: CategoryVO) {
  Object.assign(form, {
    id: row.id,
    // 根分类的 parentId 后端存的是 0，这里保持一致
    parentId: row.parentId ?? 0,
    name: row.name,
    icon: '',
    sort: row.sort ?? 0,
    status: row.status ?? 1
  })
  dialogTitle.value = `编辑分类：${row.name}`
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (form.id) {
      await updateCategory({ ...form })
      ElMessage.success('修改成功')
    } else {
      await createCategory({ ...form })
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchTree()
  } catch (error) {
    console.error('[分类管理] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: CategoryVO) {
  const hasChildren = row.children && row.children.length > 0
  try {
    await ElMessageBox.confirm(
      hasChildren
        ? `「${row.name}」下还有 ${row.children!.length} 个子分类，删除可能失败或连带影响，确定继续吗？`
        : `确定要删除分类「${row.name}」吗？`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await deleteCategory(row.id)
    ElMessage.success('删除成功')
    fetchTree()
  } catch (error) {
    console.error('[分类管理] 删除失败', error)
  }
}

onMounted(fetchTree)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">商品分类（树形，共 {{ treeData.length }} 个一级分类）</span>
        <div>
          <el-button :icon="'Refresh'" @click="fetchTree">刷新</el-button>
          <el-button v-permission="'product:category:add'" type="primary" :icon="'Plus'" @click="handleAdd()">
            新增一级分类
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
        <el-table-column prop="name" label="分类名称" min-width="220" />
        <el-table-column prop="id" label="ID" width="90" align="center" />
        <el-table-column prop="parentId" label="上级ID" width="100" align="center" />
        <el-table-column prop="sort" label="排序" width="90" align="center" />

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="240" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button
                v-permission="'product:category:add'"
                link
                type="primary"
                @click="handleAdd(row)"
              >
                新增子分类
              </el-button>
              <el-button
                v-permission="'product:category:edit'"
                link
                type="primary"
                @click="handleEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="'product:category:delete'"
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
          <el-empty description="暂无分类数据" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="上级分类" prop="parentId">
          <el-tree-select
            v-model="form.parentId"
            :data="parentTreeData"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            placeholder="请选择上级分类"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分类名称" maxlength="50" />
        </el-form-item>

        <el-form-item label="图标地址" prop="icon">
          <el-input v-model="form.icon" placeholder="选填，图片 URL" maxlength="500" />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" style="width: 100%" />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
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
</style>
