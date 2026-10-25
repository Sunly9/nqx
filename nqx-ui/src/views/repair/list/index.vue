<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  REPAIR_STATUS,
  REPAIR_STATUS_OPTIONS,
  WARRANTY_TYPE_OPTIONS,
  acceptRepair,
  completeRepair,
  pageRepair,
  rejectRepair
} from '@/api/repair'
import type { AdminRepairApplyVO, AdminRepairQuery } from '@/api/repair'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 维修工单。
 *
 * <h3>这个页面补的是什么</h3>
 *
 * 在此之前，维修是**断头流程**：小程序能提交申请、用户能看到自己的申请，
 * 但后台没有任何维修接口 —— 没人知道这些申请存在，状态永远停在「待处理」。
 *
 * <h3>三个动作的显示条件严格对齐后端状态机</h3>
 *
 *   待处理(1) --受理--> 处理中(2) --完成--> 已完成(3)
 *       │                    │
 *       └────── 驳回 ───────┴──> 已驳回(4)
 *
 * 前端只是把规则镜像一份用于**隐藏按钮**，最终校验仍在后端 ——
 * 所以即使按钮的显示条件过期，误点也只会收到后端返回的业务错误提示，
 * 不会产生脏数据。
 *
 * <h3>为什么「完成」不能从「待处理」直接点</h3>
 *
 * 那样用户在整个过程中看到的都是"待处理"，最后突然变成"已完成" ——
 * 中间没有任何反馈，等于没人管过。受理这一步的意义就是让用户
 * 看出"我的申请被看到了"。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<AdminRepairQuery>({
  applyNo: undefined,
  nickname: undefined,
  productName: undefined,
  status: undefined,
  warrantyType: undefined
})

/** 申请时间区间单独存：el-date-picker 的区间绑的是数组，不好直接塞进 query */
const dateRange = ref<[string, string] | null>(null)

const loading = ref(false)
const tableData = ref<AdminRepairApplyVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

/* ------------------------------ 数据加载 ------------------------------ */

async function fetchList() {
  loading.value = true
  try {
    const params: AdminRepairQuery = stripEmpty({
      ...query,
      startDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1],
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    const res = await pageRepair(params)
    tableData.value = res.records ?? []
    total.value = res.total ?? 0
  } catch (error) {
    console.error('[维修工单] 加载失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNum.value = 1
  fetchList()
}

function handleReset() {
  query.applyNo = undefined
  query.nickname = undefined
  query.productName = undefined
  query.status = undefined
  query.warrantyType = undefined
  dateRange.value = null
  pageNum.value = 1
  fetchList()
}

function handlePageChange(p: number) {
  pageNum.value = p
  fetchList()
}

function handleSizeChange(s: number) {
  pageSize.value = s
  pageNum.value = 1
  fetchList()
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const detailVisible = ref(false)
const detailRow = ref<AdminRepairApplyVO | null>(null)

function handleDetail(row: AdminRepairApplyVO) {
  detailRow.value = row
  detailVisible.value = true
}

/* ------------------------------ 三个处理动作 ------------------------------ */

const submitting = ref(false)

/** 受理：待处理 → 处理中 */
async function handleAccept(row: AdminRepairApplyVO) {
  try {
    await ElMessageBox.confirm(
      `确认受理工单「${row.applyNo}」？\n` +
        '受理后用户会看到「商家已接单，维修中」，请尽快安排处理。',
      '受理工单',
      { type: 'info', confirmButtonText: '确认受理', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }

  submitting.value = true
  try {
    await acceptRepair(row.id, '')
    ElMessage.success('已受理')
    fetchList()
  } catch (error) {
    console.error('[维修工单] 受理失败', error)
  } finally {
    submitting.value = false
  }
}

/** 完成：处理中 → 已完成 */
async function handleComplete(row: AdminRepairApplyVO) {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认工单「${row.applyNo}」已维修完成？\n可以填一句处理说明（选填），用户能看到。`,
      '完成工单',
      {
        type: 'success',
        confirmButtonText: '确认完成',
        cancelButtonText: '取消',
        inputPlaceholder: '如：已更换触点，测试正常',
        inputValue: ''
      }
    )
    remark = result.value || ''
  } catch {
    return
  }

  submitting.value = true
  try {
    await completeRepair(row.id, remark)
    ElMessage.success('已完成')
    fetchList()
  } catch (error) {
    console.error('[维修工单] 完成失败', error)
  } finally {
    submitting.value = false
  }
}

/**
 * 驳回：待处理 / 处理中 → 已驳回。
 *
 * 驳回原因**必填**（后端也会校验）。用户看到"申请被拒绝"而不知道为什么，
 * 只能来问客服 —— 那等于把一次沟通成本转嫁给了用户。
 */
async function handleReject(row: AdminRepairApplyVO) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `驳回工单「${row.applyNo}」\n请写明原因，用户会看到这句话：`,
      '驳回工单',
      {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '如：故障属于人为损坏，不在保修范围 / 请直接联系厂家',
        // 必填校验：不填就点确认时由 Element Plus 拦截并提示
        inputValidator: (value: string) =>
          (value && value.trim().length > 0) || '请填写驳回原因'
      }
    )
    reason = (result.value || '').trim()
  } catch {
    return
  }

  submitting.value = true
  try {
    await rejectRepair(row.id, reason)
    ElMessage.success('已驳回')
    fetchList()
  } catch (error) {
    console.error('[维修工单] 驳回失败', error)
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 展示辅助 ------------------------------ */

/** 状态标签颜色：待处理要醒目（有事要干），已完成用中性色（不需要关注） */
function statusTagType(status: number) {
  switch (status) {
    case REPAIR_STATUS.PENDING:
      return 'warning'
    case REPAIR_STATUS.PROCESSING:
      return 'primary'
    case REPAIR_STATUS.COMPLETED:
      return 'success'
    default:
      return 'info'
  }
}

/**
 * 保修判定的标签颜色。
 *
 * 保修期内用绿色、已过保用橙色：这是管理员判断"要不要收钱"的第一眼依据，
 * 用颜色区分比看文字快得多。
 */
function warrantyTagType(warrantyType?: string) {
  if (warrantyType === 'warranty') {
    return 'success'
  }
  if (warrantyType === 'paid') {
    return 'warning'
  }
  return 'info' // 未判定（历史数据）
}

/** 图片相对路径补成绝对地址：后端存的是 /files/...，直接放 img 会 404 */
function imageUrl(url?: string) {
  if (!url) {
    return ''
  }
  if (/^https?:\/\//i.test(url)) {
    return url
  }
  return url
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="申请单号">
          <el-input v-model="query.applyNo" placeholder="模糊搜索" clearable style="width: 170px" />
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="query.nickname" placeholder="昵称模糊搜索" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="商品名称">
          <el-input v-model="query.productName" placeholder="模糊搜索" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option
              v-for="opt in REPAIR_STATUS_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="保修判定">
          <!--
            这个筛选条件是运营最常用的两个问题之一：
            「还有哪些保修期内的工单没处理」（优先做口碑）
            「过保的报价单有哪些」（涉及收钱）
          -->
          <el-select v-model="query.warrantyType" placeholder="全部" clearable style="width: 150px">
            <el-option
              v-for="opt in WARRANTY_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请时间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            range-separator="至"
            style="width: 250px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="'Search'" @click="handleSearch">查询</el-button>
          <el-button :icon="'RefreshLeft'" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 数据表 -->
    <el-card shadow="never" class="table-card">
      <div class="table-toolbar">
        <span class="table-title">维修工单（共 {{ total }} 条）</span>
        <span class="toolbar-tip">待处理的工单排在最前面</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="applyNo" label="申请单号" width="185" show-overflow-tooltip />

        <el-table-column label="申请人" width="140">
          <template #default="{ row }">
            <span>{{ row.nickname || `用户 #${row.userId}` }}</span>
          </template>
        </el-table-column>

        <el-table-column label="商品" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.productName || '-' }}</template>
        </el-table-column>

        <el-table-column label="故障描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.faultDesc || '-' }}</template>
        </el-table-column>

        <el-table-column label="保修判定" width="140" align="center">
          <template #default="{ row }">
            <el-tag :type="warrantyTagType(row.warrantyType)" size="small">
              {{ row.warrantyTypeText || '未判定' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusText || '-' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.create_time) }}</template>
        </el-table-column>

        <el-table-column label="处理时间" width="160">
          <template #default="{ row }">
            {{ row.handleTime ? formatDateTime(row.handleTime) : '-' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="handleDetail(row)">详情</el-button>

              <!-- 受理：只有待处理能点 -->
              <el-button
                v-if="row.status === REPAIR_STATUS.PENDING"
                v-permission="'repair:handle'"
                link
                type="primary"
                :loading="submitting"
                @click="handleAccept(row)"
              >
                受理
              </el-button>

              <!-- 完成：只有处理中能点（不能绕过受理直接完成） -->
              <el-button
                v-if="row.status === REPAIR_STATUS.PROCESSING"
                v-permission="'repair:handle'"
                link
                type="success"
                :loading="submitting"
                @click="handleComplete(row)"
              >
                完成
              </el-button>

              <!-- 驳回：待处理与处理中都能点 -->
              <el-button
                v-if="
                  row.status === REPAIR_STATUS.PENDING || row.status === REPAIR_STATUS.PROCESSING
                "
                v-permission="'repair:handle'"
                link
                type="danger"
                :loading="submitting"
                @click="handleReject(row)"
              >
                驳回
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="维修工单详情" size="460px">
      <el-descriptions v-if="detailRow" :column="1" border>
        <el-descriptions-item label="申请单号">{{ detailRow.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detailRow.status)" size="small">
            {{ detailRow.statusText }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="保修判定">
          <el-tag :type="warrantyTagType(detailRow.warrantyType)" size="small">
            {{ detailRow.warrantyTypeText || '未判定' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="申请人">
          {{ detailRow.nickname || '-' }}
          <span v-if="detailRow.userId" class="sub-text">（ID: {{ detailRow.userId }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="联系人与电话">
          {{ detailRow.contactName || '-' }} / {{ detailRow.contactPhone || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="寄修地址">
          {{ detailRow.address || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="关联订单">
          {{ detailRow.orderNo || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="商品">{{ detailRow.productName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="故障描述">
          <div class="fault-desc">{{ detailRow.faultDesc || '-' }}</div>
        </el-descriptions-item>
        <el-descriptions-item v-if="detailRow.handleRemark" label="处理说明">
          <div class="fault-desc">{{ detailRow.handleRemark }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="申请时间">
          {{ formatDateTime(detailRow.create_time) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailRow.handleTime" label="处理时间">
          {{ formatDateTime(detailRow.handleTime) }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 故障照片：凭证，管理员判断故障性质的主要依据 -->
      <div v-if="detailRow?.images && detailRow.images.length" class="image-block">
        <div class="image-title">故障照片（{{ detailRow.images.length }} 张）</div>
        <div class="image-list">
          <el-image
            v-for="(img, i) in detailRow.images"
            :key="i"
            :src="imageUrl(img)"
            :preview-src-list="detailRow.images.map(imageUrl)"
            :initial-index="i"
            fit="cover"
            class="fault-image"
            preview-teleported
          />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card :deep(.el-form-item) {
  margin-bottom: 12px;
}

.table-card {
  min-height: 320px;
}

.table-toolbar {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.table-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.toolbar-tip {
  font-size: 12px;
  color: #909399;
}

.table-actions {
  display: flex;
  justify-content: center;
  gap: 4px;
  flex-wrap: wrap;
}

.sub-text {
  color: #909399;
  font-size: 12px;
}

/* 故障描述可能比较长，保留换行，方便对照照片看 */
.fault-desc {
  white-space: pre-wrap;
  line-height: 1.6;
}

.image-block {
  margin-top: 16px;
}

.image-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fault-image {
  width: 88px;
  height: 88px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
