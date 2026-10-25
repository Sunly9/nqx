<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditOrder, confirmRecycle, deliverOrder, exportOrders, pageOrder, rejectOrder, settleOrder } from '@/api/order'
import type { AdminOrderQuery, AdminOrderVO } from '@/types/order'
import { formatDateTime, formatWeight, stripEmpty } from '@/utils/format'
import { exportAndDownload } from '@/utils/download'

/**
 * 回收订单管理。
 *
 * 操作按钮的显示条件严格对齐后端 OrderStateMachine 的流转规则：
 *   待审核(10) --审核--> 待回收(11) / --驳回--> 已驳回(20)
 *   待回收(11) --确认回收--> 待结算(12)
 *   待结算(12) --结算--> 已完成(13)
 * 前端只是把规则镜像了一份用于隐藏按钮，最终校验仍在后端 ——
 * 因此即使按钮显示条件过期，误点也只会收到后端返回的业务错误提示，不会产生脏数据。
 */

const RECYCLE_STATUS = {
  PENDING_AUDIT: 10,
  AUDIT_PASSED: 11,
  COLLECTED: 12,
  FINISHED: 13,
  REJECTED: 20,
  CANCELLED: 30
} as const

const statusOptions = [
  { label: '待审核', value: RECYCLE_STATUS.PENDING_AUDIT },
  { label: '待回收', value: RECYCLE_STATUS.AUDIT_PASSED },
  { label: '待结算', value: RECYCLE_STATUS.COLLECTED },
  { label: '已完成', value: RECYCLE_STATUS.FINISHED },
  { label: '已驳回', value: RECYCLE_STATUS.REJECTED },
  { label: '已取消', value: RECYCLE_STATUS.CANCELLED }
]

const recycleMethodOptions = [
  { label: '上门回收', value: 'pickup' },
  { label: '邮寄回收', value: 'send' }
]

function statusTagType(status: number) {
  switch (status) {
    case RECYCLE_STATUS.PENDING_AUDIT:
      return 'warning'
    case RECYCLE_STATUS.AUDIT_PASSED:
      return 'primary'
    case RECYCLE_STATUS.COLLECTED:
      return 'info'
    case RECYCLE_STATUS.FINISHED:
      return 'success'
    case RECYCLE_STATUS.REJECTED:
      return 'danger'
    default:
      return 'info'
  }
}

function recycleMethodText(method?: string) {
  if (!method) return '-'
  return recycleMethodOptions.find((o) => o.value === method)?.label || method
}

/* ------------------------------ 检索 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  nickname: '',
  userId: undefined as number | undefined,
  status: undefined as number | undefined,
  recycleMethod: undefined as string | undefined,
  minPoints: undefined as number | undefined,
  maxPoints: undefined as number | undefined
})

/** 时间范围单独存：el-date-picker 的 datetimerange 绑的是数组，不好直接塞进 query */
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
      // 这是回收订单页，类型恒定过滤为 1，避免采购单串进来
      orderType: 1,
      status: query.status,
      recycleMethod: query.recycleMethod,
      // 后端是 LocalDateTime，这里直接给 ISO 形式的字符串，Spring 能按 ISO 解析
      startTime: timeRange.value?.[0],
      endTime: timeRange.value?.[1],
      minPoints: query.minPoints,
      maxPoints: query.maxPoints
    }
    const res = await pageOrder(stripEmpty(params))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[回收订单] 查询失败', error)
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
  query.recycleMethod = undefined
  query.minPoints = undefined
  query.maxPoints = undefined
  timeRange.value = undefined
  query.pageNum = 1
  fetchList()
}

/* ------------------------------ 导出 ------------------------------ */

/**
 * 导出当前筛选条件下的回收单。
 *
 * 与列表用同一个 query 对象（去掉分页参数），所以"列表里筛出什么，导出就是什么"。
 * 分页参数必须剔掉 —— 否则导出的是"第 2 页那 10 条"，而不是全部符合条件的。
 */
async function handleExport() {
  try {
    const params: AdminOrderQuery = stripEmpty({
      ...query,
      // 强制只看回收单，避免把采购单也导进去（本页只展示回收单）
      orderType: 1,
      startTime: timeRange.value?.[0],
      endTime: timeRange.value?.[1],
      pageNum: undefined,
      pageSize: undefined
    })
    await exportAndDownload(exportOrders(params), '回收订单')
  } catch (error) {
    // exportAndDownload 已经把后端提示抛出来了（如"没有数据可导出"），
    // 这里再弹一次会重复；只记录日志即可
    console.warn('[回收订单] 导出失败', error)
  }
}

async function handleAudit(row: AdminOrderVO) {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认审核通过订单 ${row.orderNo}？通过后订单进入「待回收」状态。`,
      '审核通过',
      {
        confirmButtonText: '确定通过',
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（选填）',
        inputValue: '',
        // 备注是选填的，因此不能配 inputValidator 强制非空；
        // 但要把 undefined 归一成空串，避免把 "null" 字符串提交上去
        inputValidator: () => true
      }
    )
    remark = result.value || ''
  } catch {
    return
  }

  try {
    await auditOrder(row.orderNo, remark || undefined)
    ElMessage.success('审核通过')
    fetchList()
  } catch (error) {
    console.error('[回收订单] 审核失败', error)
  }
}

/* ------------------------------ 驳回 ------------------------------ */

async function handleReject(row: AdminOrderVO) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `请填写驳回订单 ${row.orderNo} 的原因，该原因会展示给用户。`,
      '驳回订单',
      {
        confirmButtonText: '确定驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '如：品类不符 / 数量虚报',
        inputValidator: (value) => (value && value.trim() ? true : '驳回原因不能为空'),
        inputType: 'textarea'
      }
    )
    reason = result.value
  } catch {
    return
  }

  try {
    await rejectOrder(row.orderNo, reason)
    ElMessage.success('已驳回')
    fetchList()
  } catch (error) {
    console.error('[回收订单] 驳回失败', error)
  }
}

/* ------------------------------ 确认回收 ------------------------------ */

const confirmVisible = ref(false)
const confirmSubmitting = ref(false)
const currentOrder = ref<AdminOrderVO | null>(null)

const confirmForm = reactive({
  actual_weight: undefined as number | undefined,
  settle: true,
  audit_remark: ''
})

function handleOpenConfirm(row: AdminOrderVO) {
  currentOrder.value = row
  confirmForm.actual_weight = row.estimatedWeight ?? undefined
  confirmForm.settle = true
  confirmForm.audit_remark = ''
  confirmVisible.value = true
}

async function handleConfirmRecycle() {
  if (!currentOrder.value) return
  if (!confirmForm.actual_weight || confirmForm.actual_weight <= 0) {
    ElMessage.warning('请填写大于 0 的实际重量')
    return
  }

  confirmSubmitting.value = true
  try {
    // 字段名必须与后端 ConfirmRecycleDTO 的 @JsonProperty 一致：
    // actual_weight / audit_remark 都是下划线形式
    await confirmRecycle(currentOrder.value.orderNo, {
      actual_weight: confirmForm.actual_weight,
      settle: confirmForm.settle,
      audit_remark: confirmForm.audit_remark || undefined
    })
    ElMessage.success(confirmForm.settle ? '确认回收并结算完成' : '已确认回收，待结算')
    confirmVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[回收订单] 确认回收失败', error)
  } finally {
    confirmSubmitting.value = false
  }
}

/* ------------------------------ 单独结算 ------------------------------ */

/**
 * 待结算订单的结算动作。
 *
 * 为什么单独做一个函数而不是复用"确认回收"：
 * 「确认回收」要录实际重量并可能同时结算；而这里订单**已经**有实际重量了
 * （上一步录入过），只是一个纯结算动作。混用会让确认弹窗出现"重量已只读
 * 但仍需提交"的怪异状态。
 */
async function handleSettle(row: AdminOrderVO) {
  try {
    await ElMessageBox.confirm(
      `确认为订单「${row.orderNo}」结算并发放积分？\n` +
        '结算后订单将变为「已完成」，积分立即到账，不可撤销。',
      '结算确认',
      { type: 'warning', confirmButtonText: '确认结算', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户点了取消
  }

  try {
    await settleOrder(row.orderNo, '管理端手动结算')
    ElMessage.success('结算完成，积分已发放')
    fetchList()
  } catch (error) {
    console.error('[回收订单] 结算失败', error)
  }
}

/* ------------------------------ 详情抽屉 ------------------------------ */

const detailVisible = ref(false)
const detailRow = ref<AdminOrderVO | null>(null)

function handleDetail(row: AdminOrderVO) {
  detailRow.value = row
  detailVisible.value = true
}

/**
 * 采购单发货接口在这里也保留一份。
 *
 * 原因：order/page 接口返回的是两类订单的合集，按 orderType 过滤依赖后端条件拼接正确。
 * 万一后端过滤没生效、采购单漏进本页，至少还能就地履约，
 * 不至于出现"数据看得到但操作不了"的死角。
 */
async function handleDeliver(row: AdminOrderVO) {
  try {
    await ElMessageBox.confirm(`确认为订单 ${row.orderNo} 发货？`, '发货确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deliverOrder(row.orderNo)
    ElMessage.success('已发货')
    fetchList()
  } catch (error) {
    console.error('[回收订单] 发货失败', error)
  }
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

        <el-form-item label="回收方式">
          <el-select v-model="query.recycleMethod" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="opt in recycleMethodOptions"
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
          <!--
            导出按钮。

            权限点 order:recycle:export 在初始化数据里早就存在（也授给了超管与运营），
            但页面上一直没有按钮 —— 权限配好了却没有入口，等于这个功能不存在。
          -->
          <el-button
            v-permission="'order:recycle:export'"
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
        <span class="table-title">回收订单（共 {{ total }} 条）</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="orderNo" label="订单号" width="200" show-overflow-tooltip />
        <el-table-column prop="nickname" label="用户昵称" width="110" show-overflow-tooltip />
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.orderTypeText || '回收单' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="回收方式" width="100" align="center">
          <template #default="{ row }">{{ recycleMethodText(row.recycleMethod) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusText || '-' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="预估重量" width="100" align="right">
          <template #default="{ row }">{{ formatWeight(row.estimatedWeight) }}</template>
        </el-table-column>

        <el-table-column label="实际重量" width="100" align="right">
          <template #default="{ row }">{{ formatWeight(row.actualWeight) }}</template>
        </el-table-column>

        <el-table-column prop="totalPoints" label="积分" width="90" align="right" />

        <el-table-column label="碳减排" width="100" align="right">
          <template #default="{ row }">{{ formatWeight(row.carbonSaved) }}</template>
        </el-table-column>

        <el-table-column prop="contactName" label="联系人" width="100" show-overflow-tooltip />
        <el-table-column prop="contactPhone" label="联系电话" width="130" />

        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="250" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="handleDetail(row)">详情</el-button>

              <template
                v-if="row.orderType === 1 && row.status === RECYCLE_STATUS.PENDING_AUDIT"
              >
                <el-button
                  v-permission="'order:recycle:audit'"
                  link
                  type="success"
                  @click="handleAudit(row)"
                >
                  审核通过
                </el-button>
                <el-button
                  v-permission="'order:recycle:reject'"
                  link
                  type="danger"
                  @click="handleReject(row)"
                >
                  驳回
                </el-button>
              </template>

              <el-button
                v-else-if="row.orderType === 1 && row.status === RECYCLE_STATUS.AUDIT_PASSED"
                v-permission="'order:recycle:confirm'"
                link
                type="warning"
                @click="handleOpenConfirm(row)"
              >
                确认回收
              </el-button>

              <!--
                待结算(12) 的结算入口。

                原来这里**什么都没有**：订单被"确认回收"推进到待结算后，
                操作列就只剩「详情」了 —— 流程走不完、积分一直不发，
                而界面上看不出还差一步。

                后端 POST /admin/order/{orderNo}/settle 一直存在
                （权限码与"确认回收"相同），只是前端没接。
              -->
              <el-button
                v-else-if="row.orderType === 1 && row.status === RECYCLE_STATUS.COLLECTED"
                v-permission="'order:recycle:confirm'"
                link
                type="success"
                @click="handleSettle(row)"
              >
                结算
              </el-button>

              <el-button
                v-else-if="row.orderType === 2 && row.status === 41"
                v-permission="'order:purchase:deliver'"
                link
                type="warning"
                @click="handleDeliver(row)"
              >
                发货
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无回收订单" :image-size="80" />
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

    <!-- 确认回收弹窗 -->
    <el-dialog v-model="confirmVisible" title="确认回收" width="520px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请以现场称重结果填写实际重量"
        description="积分类结算按实际重量计算，用户自报的预估重量仅作参考。"
        class="confirm-alert"
      />

      <el-form label-width="110px">
        <el-form-item label="订单号">
          <span class="detail-value">{{ currentOrder?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="用户预估重量">
          <span class="detail-value">{{ formatWeight(currentOrder?.estimatedWeight) }}</span>
        </el-form-item>
        <el-form-item label="实际重量(kg)" required>
          <el-input-number
            v-model="confirmForm.actual_weight"
            :min="0.01"
            :precision="2"
            :step="0.1"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="同时结算">
          <el-switch v-model="confirmForm.settle" active-text="结算发放积分" inactive-text="仅确认回收" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="confirmForm.audit_remark"
            type="textarea"
            :rows="2"
            placeholder="选填"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirmSubmitting" @click="handleConfirmRecycle">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="订单详情" size="420px">
      <el-descriptions v-if="detailRow" :column="1" border>
        <el-descriptions-item label="订单号">{{ detailRow.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="订单类型">
          {{ detailRow.orderTypeText || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ detailRow.statusText || '-' }}（{{ detailRow.status }}）
        </el-descriptions-item>
        <el-descriptions-item label="用户">{{ detailRow.nickname || '-' }}</el-descriptions-item>
        <el-descriptions-item label="回收方式">
          {{ recycleMethodText(detailRow.recycleMethod) }}
        </el-descriptions-item>
        <el-descriptions-item label="预估重量">
          {{ formatWeight(detailRow.estimatedWeight) }}
        </el-descriptions-item>
        <el-descriptions-item label="实际重量">
          {{ formatWeight(detailRow.actualWeight) }}
        </el-descriptions-item>
        <el-descriptions-item label="发放积分">{{ detailRow.totalPoints ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="碳减排量">
          {{ formatWeight(detailRow.carbonSaved) }}
        </el-descriptions-item>
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

.table-title {
  font-weight: 600;
}

.confirm-alert {
  margin-bottom: 16px;
}

.detail-value {
  color: #606266;
}
</style>
