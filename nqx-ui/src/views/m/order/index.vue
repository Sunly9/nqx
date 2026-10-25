<template>
  <div class="m-page">
    <!-- ================= 订单类型 ================= -->
    <div class="m-segmented">
      <button
        v-for="t in TYPE_OPTIONS"
        :key="t.value"
        class="m-segmented__item"
        :class="{ active: orderType === t.value }"
        @click="switchType(t.value)"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ================= 状态筛选 ================= -->
    <div class="m-segmented m-segmented--sub">
      <button
        v-for="s in statusOptions"
        :key="s.label"
        class="m-segmented__item"
        :class="{ active: status === s.value }"
        @click="switchStatus(s.value)"
      >
        {{ s.label }}
      </button>
    </div>

    <!-- ================= 搜索 ================= -->
    <div class="search">
      <el-input
        v-model="keyword"
        placeholder="搜索订单号或用户昵称"
        clearable
        @keyup.enter="reload"
        @clear="reload"
      />
    </div>

    <!-- ================= 列表 ================= -->
    <div v-if="loading && list.length === 0" class="m-empty">加载中…</div>

    <template v-else>
      <div v-for="o in list" :key="o.id" class="m-card">
        <!-- 头部：订单号 + 状态 -->
        <div class="m-card__head">
          <span class="order-no">{{ o.orderNo }}</span>
          <span class="m-tag" :class="statusTagClass(o.status)">{{ o.statusText || '—' }}</span>
        </div>

        <!--
          买家催发货。
          采购单最需要优先处理的就是这类 —— 买家已经在催了。
          放在卡片最上方显眼位置，而不是藏进详情。
        -->
        <div v-if="o.remindShipText" class="remind">
          <span class="remind__dot" />
          <span>{{ o.remindShipText }}</span>
        </div>

        <div class="m-row">
          <span class="m-row__label">用户</span>
          <span class="m-row__value">{{ o.nickname || '—' }}</span>
        </div>

        <template v-if="o.orderType === 1">
          <div class="m-row">
            <span class="m-row__label">预估重量</span>
            <span class="m-row__value">{{ o.estimatedWeight ?? '—' }} kg</span>
          </div>
          <div class="m-row" v-if="o.actualWeight">
            <span class="m-row__label">实际重量</span>
            <span class="m-row__value">{{ o.actualWeight }} kg</span>
          </div>
          <div class="m-row" v-if="o.totalPoints">
            <span class="m-row__label">获得积分</span>
            <span class="m-row__value">{{ o.totalPoints }}</span>
          </div>
          <div class="m-row" v-if="o.recycleMethod">
            <span class="m-row__label">回收方式</span>
            <span class="m-row__value">{{ o.recycleMethod === 'send' ? '寄送' : '上门' }}</span>
          </div>
        </template>

        <template v-else>
          <div class="m-row">
            <span class="m-row__label">消耗积分</span>
            <span class="m-row__value">{{ o.totalPoints ?? 0 }}</span>
          </div>
          <div class="m-row" v-if="o.courierNo">
            <span class="m-row__label">快递单号</span>
            <span class="m-row__value">{{ o.courierNo }}</span>
          </div>
        </template>

        <div class="m-row">
          <span class="m-row__label">下单时间</span>
          <span class="m-row__value">{{ formatTime(o.createTime) }}</span>
        </div>

        <!-- 操作区：只显示当前状态真的能做的动作 -->
        <div v-if="actionsOf(o).length" class="actions">
          <el-button
            v-for="a in actionsOf(o)"
            :key="a.key"
            :type="a.type"
            size="large"
            @click="a.run(o)"
          >
            {{ a.label }}
          </el-button>
        </div>
      </div>

      <div v-if="list.length === 0" class="m-empty">没有符合条件的订单</div>

      <div v-if="list.length > 0" class="more">
        <el-button v-if="hasMore" :loading="loading" text @click="loadMore">加载更多</el-button>
        <span v-else class="more__end">没有更多了</span>
      </div>
    </template>

    <!-- ================= 操作弹窗 ================= -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" class="m-dialog" align-center>
      <p v-if="dialogTip" class="dialog-tip">{{ dialogTip }}</p>

      <!-- 驳回原因 -->
      <el-input
        v-if="dialogKind === 'reject'"
        v-model="dialogText"
        type="textarea"
        :rows="3"
        placeholder="请填写驳回原因（买家能看到）"
      />

      <!-- 发货：快递单号 -->
      <el-input
        v-if="dialogKind === 'deliver'"
        v-model="dialogText"
        placeholder="快递单号（选填，填了买家能看到物流）"
      />

      <!-- 确认回收：实际重量 -->
      <div v-if="dialogKind === 'recycle'" class="dialog-field">
        <label class="m-field__label"><span class="req">*</span>实际重量（kg）</label>
        <el-input v-model="dialogWeight" type="number" inputmode="decimal" placeholder="现场称重后的重量" />
        <el-checkbox v-model="dialogSettle" style="margin-top: 12px">
          同时结算并发放积分
        </el-checkbox>
      </div>

      <template #footer>
        <el-button size="large" @click="dialogVisible = false">取消</el-button>
        <el-button size="large" type="primary" :loading="submitting" @click="submitDialog">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  auditOrder,
  confirmRecycle,
  deliverOrder,
  pageOrder,
  rejectOrder,
  settleOrder
} from '@/api/order'
import type { AdminOrderVO } from '@/types/order'

/**
 * 移动端订单管理 —— 回收单与采购单合并在一个页面。
 *
 * <h2>为什么两类订单放一页</h2>
 *
 * 桌面版是「回收订单」「采购订单」两个菜单。手机上如果也分成两个 Tab，
 * 底部导航会被撑到 5 个以上，而且管理员的心智是"我要处理订单"，
 * 不是"我要处理某一类订单"。
 *
 * 所以这里用一个顶部分段切换类型，下面再分段筛状态，两层都只有一行。
 *
 * <h2>状态码</h2>
 *
 * 回收：10 待审核 / 11 待回收 / 12 待结算 / 13 已完成 / 20 已驳回
 * 采购：40 待支付 / 41 待发货 / 42 待收货 / 43 已完成 / 50 已关闭
 */

const route = useRoute()

const TYPE_OPTIONS = [
  { label: '回收单', value: 1 },
  { label: '采购单', value: 2 }
] as const

const RECYCLE_STATUS = [
  { label: '全部', value: undefined as number | undefined },
  { label: '待审核', value: 10 },
  { label: '待回收', value: 11 },
  { label: '待结算', value: 12 },
  { label: '已完成', value: 13 },
  { label: '已驳回', value: 20 }
] as const

const PURCHASE_STATUS = [
  { label: '全部', value: undefined as number | undefined },
  { label: '待支付', value: 40 },
  { label: '待发货', value: 41 },
  { label: '待收货', value: 42 },
  { label: '已完成', value: 43 }
] as const

const PAGE_SIZE = 10

const orderType = ref<number>(1)
const status = ref<number | undefined>(undefined)
const keyword = ref('')
const list = ref<AdminOrderVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

const statusOptions = computed(() =>
  orderType.value === 1 ? RECYCLE_STATUS : PURCHASE_STATUS
)

const hasMore = computed(() => list.value.length < total.value)

// ---------------------------------------------------------------------------
// 弹窗状态
// ---------------------------------------------------------------------------
const dialogVisible = ref(false)
const dialogKind = ref<'reject' | 'deliver' | 'recycle' | 'audit' | 'settle'>('audit')
const dialogText = ref('')
const dialogWeight = ref('')
const dialogSettle = ref(true)
const submitting = ref(false)
const currentOrder = ref<AdminOrderVO | null>(null)

const dialogTitle = computed(() => {
  switch (dialogKind.value) {
    case 'reject': return '驳回回收申请'
    case 'deliver': return '确认发货'
    case 'recycle': return '确认回收（称重）'
    case 'settle': return '确认结算'
    default: return '审核通过'
  }
})

const dialogTip = computed(() => {
  const no = currentOrder.value?.orderNo ?? ''
  switch (dialogKind.value) {
    case 'reject': return `订单 ${no}：驳回后买家会看到你填写的原因`
    case 'deliver': return `订单 ${no}：发货后订单进入「待收货」`
    case 'recycle': return `订单 ${no}：请按现场称重的实际重量填写`
    case 'settle': return `订单 ${no}：结算后积分发放到买家账户`
    default: return `订单 ${no}：审核通过后买家可以寄出物品`
  }
})

// ---------------------------------------------------------------------------
// 数据
// ---------------------------------------------------------------------------

function statusTagClass(code: number): string {
  // 进行中/待办的用醒目色，结束态用灰色，避免满屏红黄
  if (code === 10 || code === 41) return 'm-tag--warning'
  if (code === 12) return 'm-tag--primary'
  if (code === 20 || code === 50) return 'm-tag--danger'
  if (code === 13 || code === 43) return 'm-tag--success'
  return 'm-tag--info'
}

function formatTime(v?: string): string {
  if (!v) return '—'
  // 后端是 ISO（2026-10-21T16:42:38），换成手机上好读的形式
  const t = new Date(String(v).replace(' ', 'T'))
  if (Number.isNaN(t.getTime())) return String(v)
  const p = (n: number) => (n < 10 ? '0' + n : String(n))
  return `${t.getMonth() + 1}-${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}`
}

async function fetchPage(reset: boolean) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) pageNum.value = 1
    const res = await pageOrder({
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
      orderType: orderType.value,
      status: status.value,
      orderNo: keyword.value.trim() || undefined
    })
    const records = res.records ?? []
    list.value = reset ? records : [...list.value, ...records]
    total.value = Number(res.total ?? 0)
  } catch (error) {
    console.error('[移动端订单] 加载失败', error)
    ElMessage.error('加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function reload() {
  fetchPage(true)
}

function loadMore() {
  pageNum.value += 1
  fetchPage(false)
}

function switchType(value: number) {
  if (orderType.value === value) return
  orderType.value = value
  // 状态码在两套枚举里含义完全不同（10 是回收的待审核，采购里压根没这个状态），
  // 换类型时必须清掉状态筛选，否则会查出一堆空结果
  status.value = undefined
  list.value = []
  total.value = 0
  reload()
}

function switchStatus(value: number | undefined) {
  if (status.value === value) return
  status.value = value
  list.value = []
  total.value = 0
  reload()
}

// ---------------------------------------------------------------------------
// 操作
// ---------------------------------------------------------------------------

interface Action {
  key: string
  label: string
  type: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  run: (o: AdminOrderVO) => void
}

/**
 * 当前订单能执行的动作。
 *
 * 每个动作都严格对应后端状态机允许的流转 —— 界面上不该出现
 * "点了会报错"按钮，那和假按钮是一个性质。
 */
function actionsOf(o: AdminOrderVO): Action[] {
  const actions: Action[] = []

  if (o.orderType === 1) {
    if (o.status === 10) {
      actions.push({ key: 'audit', label: '通过', type: 'success', run: (x) => openDialog('audit', x) })
      actions.push({ key: 'reject', label: '驳回', type: 'danger', run: (x) => openDialog('reject', x) })
    } else if (o.status === 11) {
      actions.push({ key: 'recycle', label: '确认收货并称重', type: 'primary', run: (x) => openDialog('recycle', x) })
    } else if (o.status === 12) {
      actions.push({ key: 'settle', label: '结算', type: 'primary', run: (x) => openDialog('settle', x) })
    }
  } else {
    if (o.status === 41) {
      actions.push({ key: 'deliver', label: '发货', type: 'primary', run: (x) => openDialog('deliver', x) })
    }
  }

  return actions
}

function openDialog(kind: typeof dialogKind.value, order: AdminOrderVO) {
  currentOrder.value = order
  dialogKind.value = kind
  dialogText.value = kind === 'deliver' ? (order.courierNo ?? '') : ''
  dialogWeight.value = ''
  dialogSettle.value = true
  dialogVisible.value = true
}

async function submitDialog() {
  const order = currentOrder.value
  if (!order) return

  submitting.value = true
  try {
    switch (dialogKind.value) {
      case 'audit':
        await auditOrder(order.orderNo)
        ElMessage.success('已通过')
        break

      case 'reject':
        if (!dialogText.value.trim()) {
          ElMessage.warning('请填写驳回原因')
          return
        }
        await rejectOrder(order.orderNo, dialogText.value.trim())
        ElMessage.success('已驳回')
        break

      case 'recycle': {
        const weight = Number(dialogWeight.value)
        if (!Number.isFinite(weight) || weight <= 0) {
          ElMessage.warning('请填写有效的实际重量')
          return
        }
        await confirmRecycle(order.orderNo, {
          actual_weight: weight,
          settle: dialogSettle.value
        })
        ElMessage.success(dialogSettle.value ? '已确认并结算' : '已确认回收')
        break
      }

      case 'settle':
        await settleOrder(order.orderNo)
        ElMessage.success('已结算')
        break

      case 'deliver':
        await deliverOrder(order.orderNo, dialogText.value.trim() || undefined)
        ElMessage.success('已发货')
        break
    }

    dialogVisible.value = false
    reload()
  } catch (error) {
    console.error('[移动端订单] 操作失败', error)
    ElMessage.error('操作失败，请重试')
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 初始化：支持从工作台带参数进来（?type=2&status=41）
// ---------------------------------------------------------------------------
onMounted(() => {
  const t = Number(route.query.type)
  if (t === 1 || t === 2) orderType.value = t
  const s = Number(route.query.status)
  if (Number.isFinite(s)) status.value = s
  reload()
})
</script>

<style scoped>
.m-segmented--sub {
  padding-top: 8px;
}

.search {
  padding: 10px 12px 0;
}

.order-no {
  font-size: 13px;
  color: var(--m-text-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 买家催发货提示 */
.remind {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(245, 34, 45, 0.06);
  color: var(--m-danger);
  font-size: 12px;
}

.remind__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--m-danger);
}

/* 操作区 */
.actions {
  display: flex;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--m-line);
}

.actions .el-button {
  flex: 1;
  margin: 0;
  height: 40px;
}

/* 加载更多 */
.more {
  padding: 4px 0 20px;
  text-align: center;
}

.more__end {
  font-size: 12px;
  color: var(--m-text-3);
}

/* 弹窗内的提示与字段 */
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--m-text-2);
}

.dialog-field {
  margin-top: 4px;
}
</style>
