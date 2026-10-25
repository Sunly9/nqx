<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createRecycleCategory,
  deleteRecycleCategory,
  pageRecycleCategory,
  updateRecycleCategory
} from '@/api/recycleCategory'
import type { RecycleCategoryQuery, RecycleCategoryVO } from '@/types/product'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 回收品类管理。
 *
 * pointsPerKg（每公斤可得积分）是回收单计价的核心字段：
 * 用户端「预估重量 × 每公斤积分」直接决定用户预期，因此这里做成必填且有下限校验，
 * 不允许出现负数（那会变成倒扣用户积分）。
 */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  name: '',
  status: undefined as number | undefined
})

const loading = ref(false)
const tableData = ref<RecycleCategoryVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: RecycleCategoryQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      name: query.name || undefined,
      status: query.status
    }
    const res = await pageRecycleCategory(stripEmpty(params))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[回收品类] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.name = ''
  query.status = undefined
  query.pageNum = 1
  fetchList()
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增回收品类')
const submitting = ref(false)
const formRef = ref<FormInstance>()

interface RecycleForm {
  id?: number
  name: string
  unit: string
  pointsPerKg?: number
  description: string
  status: number
  sort: number
}

function emptyForm(): RecycleForm {
  return {
    id: undefined,
    name: '',
    unit: 'kg',
    pointsPerKg: undefined,
    description: '',
    status: 1,
    sort: 0
  }
}

const form = reactive<RecycleForm>(emptyForm())

const rules: FormRules = {
  name: [
    { required: true, message: '请输入品类名称', trigger: 'blur' },
    { max: 50, message: '品类名称不能超过 50 个字符', trigger: 'blur' }
  ],
  unit: [{ required: true, message: '请输入计量单位', trigger: 'blur' }],
  pointsPerKg: [{ required: true, message: '请输入每公斤可获积分', trigger: 'blur' }]
}

function handleAdd() {
  Object.assign(form, emptyForm())
  dialogTitle.value = '新增回收品类'
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function handleEdit(row: RecycleCategoryVO) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    unit: row.unit,
    pointsPerKg: row.pointsPerKg,
    description: row.description ?? '',
    status: row.status,
    sort: row.sort ?? 0
  })
  dialogTitle.value = `编辑品类：${row.name}`
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
      await updateRecycleCategory({ ...form })
      ElMessage.success('修改成功')
    } else {
      await createRecycleCategory({ ...form })
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[回收品类] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: RecycleCategoryVO) {
  try {
    await ElMessageBox.confirm(
      `确定要删除回收品类「${row.name}」吗？已被历史订单引用的品类建议改为禁用而非删除。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch {
    return
  }

  try {
    await deleteRecycleCategory(row.id)
    ElMessage.success('删除成功')
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    fetchList()
  } catch (error) {
    console.error('[回收品类] 删除失败', error)
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="品类名称">
          <el-input
            v-model="query.name"
            placeholder="支持模糊匹配"
            clearable
            style="width: 200px"
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

    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">回收品类（共 {{ total }} 条）</span>
        <el-button
          v-permission="'recycle:category:add'"
          type="primary"
          :icon="'Plus'"
          @click="handleAdd"
        >
          新增品类
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="name" label="品类名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="80" align="center" />
        <el-table-column prop="pointsPerKg" label="每公斤积分" width="110" align="right" />
        <el-table-column prop="description" label="回收说明" min-width="240" show-overflow-tooltip />
        <el-table-column prop="sort" label="排序" width="80" align="center" />

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button
                v-permission="'recycle:category:edit'"
                link
                type="primary"
                @click="handleEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                v-permission="'recycle:category:delete'"
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
          <el-empty description="暂无回收品类" :image-size="80" />
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
          @size-change="handleSearch"
          @current-change="fetchList"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="品类名称" prop="name">
          <el-input v-model="form.name" placeholder="如：锂锰纽扣电池（CR 系列）" maxlength="50" />
        </el-form-item>

        <el-form-item label="计量单位" prop="unit">
          <el-input v-model="form.unit" placeholder="如 kg / 个" maxlength="20" />
        </el-form-item>

        <el-form-item label="每公斤积分" prop="pointsPerKg">
          <el-input-number v-model="form.pointsPerKg" :min="0" style="width: 100%" />
        </el-form-item>

        <el-form-item label="回收说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="选填"
          />
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
