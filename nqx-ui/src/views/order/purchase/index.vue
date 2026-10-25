<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deliverOrder, exportOrders, pageOrder } from '@/api/order'
import type { AdminOrderQuery, AdminOrderVO } from '@/types/order'
import { formatDateTime, stripEmpty } from '@/utils/format'
import { exportAndDownload } from '@/utils/download'

/**
 * 采购订单管理。
 *
 * 采购单的状态机（后端 OrderStateMachine）：
 *   待支付(40) --支付--> 待发货(41) --发货--> 待收货(42) --确认收货--> 已完成(43)
 * 管理端唯一能做的动作是「发货」，因此本页操作列只有这一个按钮；
 * 其余状态流转由小程序端或定时任务驱动。
 */

const PURCHASE_STATUS = {
  PENDING_PAY: 40,
  PAID: 41,
  DELIVERED: 42,
  FINISHED: 43,
  CLOSED: 50,
  CANCELLED: 30
} as const

const statusOptions = [
  { label: '待支付', value: PURCHASE_STATUS.PENDING_PAY },
  { label: '待发货', value: PURCHASE_STATUS.PAID },
  { label: '待收货', value: PURCHASE_STATUS.DELIVERED },
  { label: '已完成', value: PURCHASE_STATUS.FINISHED },
  { label: '已关闭', value: PURCHASE_STATUS.CLOSED },
  { label: '已取消', value: PURCHASE_STATUS.CANCELLED }
]

function statusTagType(status: number) {
  switch (status) {
    case PURCHASE_STATUS.PENDING_PAY:
      return 'warning'
    case PURCHASE_STATUS.PAID:
      return 'danger'
    case PURCHASE_STATUS.DELIVERED:
      return 'primary'
    case PURCHASE_STATUS.FINISHED:
      return 'success'
    default:
      return 'info'
  }
}

/* ------------------------------ 检索 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  nickname: '',
  userId: undefined as number | undefined,
  status: undefined as number | undefined,
  minPoints: undefined as number | undefined,
  maxPoints: undefined as number | undefined
})

const timeRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<AdminOrderVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: AdminOrderQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      orderNo: query.orderNo || undefined,
      nickname: query.nickname || undefined,
      userId: query.userId,
      // 采购订单页固定过滤 orderType=2（1 是回收单）
      orderType: 2,
      status: query.status,
      startTime: timeRange.value?.[0],
      endTime: timeRange.value?.[1],
      minPoints: query.minPoints,
      maxPoints: query.maxPoints
    }
    const res = await pageOrder(stripEmpty(params))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[采购订单] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.orderNo = ''
  query.nickname = ''
  query.userId = undefined
  query.status = undefined
  query.minPoints = undefined
  query.maxPoints = undefined
  timeRange.value = undefined
  query.pageNum = 1
  fetchList()
}

/* ------------------------------ 导出 ------------------------------ */

/**
 * 导出当前筛选条件下的采购单。
 *
 * 与列表共用同一个 query（去掉分页参数），保证"列表筛出什么、导出就是什么"。
 * `orderType` 强制为 2：本页只展示采购单，导出也不该混进回收单。
 */
async function handleExport() {
  try {
    const params: AdminOrderQuery = stripEmpty({
      ...query,
      orderType: 2,
      startTime: timeRange.value?.[0],
      endTime: timeRange.value?.[1],
      pageNum: undefined,
      pageSize: undefined
    })
    await exportAndDownload(exportOrders(params), '采购订单')
  } catch (error) {
    // exportAndDownload 已把后端提示弹出，这里只记日志避免重复提示
    console.warn('[采购订单] 导出失败', error)
  }
}

/* ------------------------------ 发货 ------------------------------ */

/**
 * 发货时让管理员填快递单号。
 *
 * 注意：后端 POST /admin/order/{orderNo}/deliver 目前<b>不接收任何参数</b>，
 * 因此这里录入的单号无法提交，仅作为提醒保留在界面上。
 * 详见交付报告「需要后端配合调整」一节。
 */
const deliverVisible = ref(false)
const deliverSubmitting = ref(false)
const currentOrder = ref<AdminOrderVO | null>(null)
const deliverForm = reactive({ courierNo: '' })

function handleOpenDeliver(row: AdminOrderVO) {
  currentOrder.value = row
  deliverForm.courierNo = row.courierNo ?? ''
  deliverVisible.value = true
}

async function handleDeliver() {
  if (!currentOrder.value) return
  if (!deliverForm.courierNo.trim()) {
    ElMessage.warning('请填写快递单号')
    return
  }
  deliverSubmitting.value = true
  try {
    /*
     * ⚠️ 必须把快递单号一起传出去。
     *
     * 原来这里只传了 orderNo，而弹窗里「请填写快递单号」是必填校验 ——
     * 管理员认真填了单号，提交时被静默丢弃，用户在小程序里看不到物流信息，
     * 前后端还都不报错。这类"填了但没用"的问题比报错更难发现。
     */
    await deliverOrder(currentOrder.value.orderNo, deliverForm.courierNo.trim())
    ElMessage.success('已发货')
    deliverVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[采购订单] 发货失败', error)
  } finally {
    deliverSubmitting.value = false
  }
}

/* ------------------------------ 详情 ------------------------------ */

const detailVisible = ref(false)
const detailRow = ref<AdminOrderVO | null>(null)

function handleDetail(row: AdminOrderVO) {
  detailRow.value = row
  detailVisible.value = true
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="订单号">
          <el-input
            v-model="query.orderNo"
            placeholder="支持模糊匹配"
            clearable
            style="width: 190px"
            @keyup.enter="handleSearch"
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

        <el-form-item label="用户ID">
          <el-input-number
            v-model="query.userId"
            :min="1"
            :controls="false"
            placeholder="精确匹配"
            style="width: 120px"
          />
        </el-form-item>

        <el-form-item label="订单状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="提交时间">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>

        <el-form-item label="积分区间">
          <el-input-number
            v-model="query.minPoints"
            :min="0"
            :controls="false"
            placeholder="最低"
            style="width: 100px"
          />
          <span class="range-sep">-</span>
          <el-input-number
            v-model="query.maxPoints"
            :min="0"
            :controls="false"
            placeholder="最高"
            style="width: 100px"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :icon="'Search'" @click="handleSearch">查询</el-button>
          <el-button :icon="'RefreshLeft'" @click="handleReset">重置</el-button>
          <!-- 权限点 order:purchase:export 早已存在，只是页面上一直没有入口 -->
          <el-button
            v-permission="'order:purchase:export'"
            type="success"
            :icon="'Download'"
            :disabled="total === 0"
            @click="handleExport"
          >
            导出
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">采购订单（共 {{ total }} 条）</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="orderNo" label="订单号" width="200" show-overflow-tooltip />
        <el-table-column prop="nickname" label="用户昵称" width="110" show-overflow-tooltip />

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusText || '-' }}
            </el-tag>
          </template>
        </el-table-column>

        <!--
          买家催发货。
          买家在小程序订单页点「提醒发货」后，这里会显示催了几次、最近一次是什么时候。
          没催过的显示占位符，避免整列空白让人以为是功能坏了。
        -->
        <el-table-column label="买家催发货" width="150" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.remindShipText"
              :content="`买家已提醒 ${row.remindShipCount || 1} 次，请尽快发货`"
              placement="top"
            >
              <el-tag type="danger" size="small" effect="plain">
                {{ row.remindShipText }}
              </el-tag>
            </el-tooltip>
            <span v-else class="remind-none">—</span>
          </template>
        </el-table-column>

        <el-table-column prop="totalPoints" label="消耗/获得积分" width="130" align="right" />

        <el-table-column prop="contactName" label="联系人" width="110" show-overflow-tooltip />
        <el-table-column prop="contactPhone" label="联系电话" width="130" />
        <el-table-column prop="courierNo" label="快递单号" width="160" show-overflow-tooltip />

        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="完成时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.finishTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="handleDetail(row)">详情</el-button>
              <el-button
                v-if="row.status === PURCHASE_STATUS.PAID"
                v-permission="'order:purchase:deliver'"
                link
                type="warning"
                @click="handleOpenDeliver(row)"
              >
                发货
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无采购订单" :image-size="80" />
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

    <!-- 发货弹窗 -->
    <el-dialog v-model="deliverVisible" title="采购单发货" width="480px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="当前后端发货接口不接收快递单号参数"
        description="POST /admin/order/{orderNo}/deliver 无请求体，此处填写的单号仅本地校验、不会提交。建议后端补充入参。"
        class="deliver-alert"
      />

      <el-form label-width="100px">
        <el-form-item label="订单号">
          <span class="detail-value">{{ currentOrder?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="收货人">
          <span class="detail-value">
            {{ currentOrder?.contactName || '-' }} / {{ currentOrder?.contactPhone || '-' }}
          </span>
        </el-form-item>
        <el-form-item label="快递单号" required>
          <el-input v-model="deliverForm.courierNo" placeholder="请输入快递单号" clearable />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="deliverVisible = false">取消</el-button>
        <el-button type="primary" :loading="deliverSubmitting" @click="handleDeliver">
          确认发货
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="订单详情" size="420px">
      <el-descriptions v-if="detailRow" :column="1" border>
        <el-descriptions-item label="订单号">{{ detailRow.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="订单类型">
          {{ detailRow.orderTypeText || '采购单' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ detailRow.statusText || '-' }}（{{ detailRow.status }}）
        </el-descriptions-item>
        <el-descriptions-item label="用户">{{ detailRow.nickname || '-' }}</el-descriptions-item>
        <el-descriptions-item label="积分">{{ detailRow.totalPoints ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ detailRow.contactName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ detailRow.contactPhone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="快递单号">{{ detailRow.courierNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">
          {{ formatDateTime(detailRow.createTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="完成时间">
          {{ formatDateTime(detailRow.finishTime) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<style scoped>
.range-sep {
  margin: 0 6px;
  color: #909399;
}

/* 没有催发货记录时的占位符，弱化显示，避免和真实消息抢注意力 */
.remind-none {
  color: #c0c4cc;
}

.table-title {
  font-weight: 600;
}

.deliver-alert {
  margin-bottom: 16px;
}

.detail-value {
  color: #606266;
}
</style>
