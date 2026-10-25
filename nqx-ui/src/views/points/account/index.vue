<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  adjustPoints,
  pagePointsAccount,
  type AdminPointsQuery,
  type PointsAccountVO
} from '@/api/points'
import { formatDateTime, genRequestId, stripEmpty } from '@/utils/format'

/**
 * 积分账户。
 *
 * 一个用户一行，只展示"当前余额 + 累计获得/消耗"。
 * 具体的每一笔变动在「积分流水」页，两页共用同一套筛选条件
 * （后端也是同一个 AdminPointsQuery），这样"发现某个用户积分异常 →
 * 切过去看他的流水"是一条顺手路径。
 *
 * 关于三个金额字段：balance 应当恒等于 totalEarn - totalSpend。
 * 页面上把它们并排放在一起，就是为了让这个恒等式<b>一眼可验</b> ——
 * 不相等说明有代码绕过 PointsService 直接改了余额，属于必须立刻排查的问题。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  userId: undefined as number | undefined,
  nickname: ''
})

/** 创建时间范围单独存：el-date-picker 的区间绑的是数组，不好直接塞进 query */
const createRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<PointsAccountVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: AdminPointsQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      userId: query.userId,
      nickname: query.nickname || undefined,
      startDate: createRange.value?.[0],
      endDate: createRange.value?.[1]
    }
    const res = await pagePointsAccount(stripEmpty(params))
    // 数据为空时后端返回 records: []，这里再兜一层 || []，
    // 避免任何一个字段缺失就让整个表格渲染失败（空数据必须优雅显示）
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[积分账户] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.userId = undefined
  query.nickname = ''
  createRange.value = undefined
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

/* ------------------------------ 调整积分 ------------------------------ */

const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  user_id: undefined as number | undefined,
  /** 有符号：正数加分、负数扣分，不能为 0 */
  points: undefined as number | undefined,
  remark: '',
  /** 幂等键由前端生成，双击/重试时沿用同一个值，避免用户被重复加扣分 */
  request_id: ''
})

const rules: FormRules = {
  user_id: [{ required: true, message: '请输入用户 ID', trigger: 'blur' }],
  points: [
    { required: true, message: '请输入调整积分', trigger: 'blur' },
    {
      // 后端 DTO 注释明确要求不能为 0，这里做前置校验省一次无效请求
      validator: (_rule, value, callback) => {
        if (value === 0) callback(new Error('调整积分不能为 0'))
        else callback()
      },
      trigger: 'blur'
    }
  ],
  remark: [
    { required: true, message: '请填写调整原因', trigger: 'blur' },
    { max: 200, message: '调整原因不能超过 200 个字符', trigger: 'blur' }
  ]
}

/**
 * 打开弹窗。
 *
 * 从列表行的「调整积分」进入时带上 userId，省去管理员照着列表手抄 ID ——
 * 手抄错一位就会给别的用户加分，而这类错误在流水里看起来完全正常。
 */
function handleOpen(row?: PointsAccountVO) {
  Object.assign(form, {
    user_id: row?.userId,
    points: undefined,
    remark: '',
    // 每次打开弹窗生成新的幂等键；提交失败后重试仍用同一个，
    // 这样"其实成功了但响应丢了"的场景不会变成扣两次
    request_id: genRequestId()
  })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.user_id === undefined || form.points === undefined) return

  submitting.value = true
  try {
    await adjustPoints({
      user_id: form.user_id,
      points: form.points,
      remark: form.remark,
      request_id: form.request_id
    })
    ElMessage.success(form.points > 0 ? '加分成功' : '扣分成功')
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[积分账户] 调整失败', error)
  } finally {
    submitting.value = false
  }
}

/** 余额颜色：0 分用灰色，正数用主色，便于快速扫出"谁积分特别多" */
function balanceClass(balance?: number) {
  if (!balance) return 'text-muted'
  return 'text-primary'
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="用户ID">
          <el-input-number
            v-model="query.userId"
            :min="1"
            :controls="false"
            placeholder="精确匹配"
            style="width: 130px"
          />
        </el-form-item>

        <el-form-item label="用户昵称">
          <el-input
            v-model="query.nickname"
            placeholder="支持模糊匹配"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="开户时间">
          <el-date-picker
            v-model="createRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 260px"
          />
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
        <span class="table-title">积分账户（共 {{ total }} 条）</span>
        <el-button
          v-permission="'points:account:adjust'"
          type="primary"
          :icon="'Edit'"
          @click="handleOpen()"
        >
          调整积分
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="userId" label="用户ID" width="100" align="center" />
        <el-table-column prop="nickname" label="用户昵称" min-width="140">
          <template #default="{ row }">
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <span v-else class="text-muted">用户已删除</span>
          </template>
        </el-table-column>

        <el-table-column label="当前余额" width="120" align="right">
          <template #default="{ row }">
            <span :class="balanceClass(row.balance)">{{ row.balance ?? 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="累计获得" width="120" align="right">
          <template #default="{ row }">{{ row.totalEarn ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="累计消耗" width="120" align="right">
          <template #default="{ row }">{{ row.totalSpend ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="账户更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button
                v-permission="'points:account:adjust'"
                link
                type="primary"
                @click="handleOpen(row)"
              >
                调整积分
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无积分账户数据" :image-size="80" />
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

    <!-- 调整积分弹窗 -->
    <el-dialog v-model="dialogVisible" title="调整用户积分" width="480px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="请谨慎操作"
        description="积分为正数表示增加、负数表示扣减，调整后会生成一条积分流水且不可撤销。"
        class="dialog-alert"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="用户 ID" prop="user_id">
          <el-input-number
            v-model="form.user_id"
            :min="1"
            :controls="false"
            placeholder="请输入用户 ID"
            style="width: 100%"
          />
        </el-form-item>

        <el-form-item label="调整积分" prop="points">
          <el-input-number v-model="form.points" :controls="false" style="width: 100%" />
          <div class="form-tip">正数增加、负数扣减，不能为 0</div>
        </el-form-item>

        <el-form-item label="调整原因" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="必填，会记录到积分流水"
          />
        </el-form-item>

        <el-form-item label="幂等键">
          <el-input v-model="form.request_id" readonly />
          <div class="form-tip">由前端自动生成，重复提交不会重复加扣分</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确认调整</el-button>
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

.text-primary {
  color: var(--nqx-primary);
  font-weight: 600;
}

.dialog-alert {
  margin-bottom: 16px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
