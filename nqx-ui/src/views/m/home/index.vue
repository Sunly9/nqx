<template>
  <div class="m-page">
    <!-- ================= 我的数据 ================= -->
    <section class="hero">
      <div class="hero__avatar">{{ avatarText }}</div>
      <div class="hero__info">
        <div class="hero__name">{{ userStore.nickname }}</div>
        <div class="hero__role">{{ roleText }}</div>
      </div>
    </section>

    <!-- ================= 待办 ================= -->
    <section class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">待办事项</span>
        <span class="todo__hint">点一下直接去处理</span>
      </div>

      <div v-if="loadingTodo" class="todo__loading">加载中…</div>
      <div v-else class="todo">
        <button
          v-for="item in todos"
          :key="item.key"
          class="todo__item"
          :class="{ 'todo__item--zero': item.count === 0 }"
          @click="goTodo(item)"
        >
          <span class="todo__count">{{ item.count }}</span>
          <span class="todo__label">{{ item.label }}</span>
        </button>
      </div>
    </section>

    <!-- ================= 总览数据 ================= -->
    <section class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">平台总览</span>
      </div>

      <div class="stats">
        <div v-for="s in stats" :key="s.label" class="stats__item">
          <div class="stats__value">{{ s.value }}</div>
          <div class="stats__label">{{ s.label }}</div>
        </div>
      </div>
    </section>

    <!-- ================= 快捷入口 ================= -->
    <section class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">快捷操作</span>
      </div>

      <div class="quick">
        <button class="quick__item" @click="router.push('/m/product/edit')">
          <span class="quick__icon quick__icon--primary">＋</span>
          <span>新增商品</span>
        </button>
        <button class="quick__item" @click="router.push('/m/orders?type=2&status=41')">
          <span class="quick__icon quick__icon--warning">↑</span>
          <span>去发货</span>
        </button>
        <button class="quick__item" @click="router.push('/m/orders?type=1&status=10')">
          <span class="quick__icon quick__icon--success">✓</span>
          <span>审核回收</span>
        </button>
        <button class="quick__item" @click="router.push('/m/product')">
          <span class="quick__icon quick__icon--info">▦</span>
          <span>商品上下架</span>
        </button>
      </div>
    </section>

    <!-- ================= 热门商品 ================= -->
    <section v-if="topProducts.length" class="m-card">
      <div class="m-card__head">
        <span class="m-card__title">热门商品</span>
      </div>
      <div v-for="(p, i) in topProducts" :key="i" class="rank">
        <span class="rank__no" :class="{ 'rank__no--top': i < 3 }">{{ i + 1 }}</span>
        <span class="rank__name">{{ p.productName || '—' }}</span>
        <span class="rank__count">{{ toNumber(p.totalQuantity) }} 件</span>
      </div>
    </section>

    <p class="footnote">数据来自后端实时聚合，非本地模拟</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboardOverview, getOrderStatus, getTopProduct } from '@/api/dashboard'
import type { DashboardOverview, OrderStatusStat, TopProductItem } from '@/api/dashboard'
import { useUserStore } from '@/stores/user'

/**
 * 移动端工作台。
 *
 * 内容取舍只按一个标准：**手机上打开第一眼最想知道什么**。
 *
 *   · 待办数量 —— 最有用。管理员打开就是想看"有没有活要干"，
 *     点进去直接到筛选好的列表，不用再翻菜单。
 *   · 平台总览 —— 六个数，一屏看完。
 *   · 快捷操作 —— 把最常用的四个动作提出来，省掉"进列表再切筛选"。
 *   · 热门商品 —— 次要信息，放最后，没有数据就不显示。
 *
 * 桌面版看板有趋势折线图、状态饼图等；那些在手机上既看不清也不好点，
 * 这里刻意不做。想看图表请用电脑版。
 */

const router = useRouter()
const userStore = useUserStore()

const loadingTodo = ref(true)
const overview = ref<DashboardOverview>({})
const statusRows = ref<OrderStatusStat[]>([])
const topProducts = ref<TopProductItem[]>([])

/** 头像占位：取昵称首字符 */
const avatarText = computed(() => (userStore.nickname || '?').slice(0, 1))

const roleText = computed(() => {
  const roles = userStore.roles
  if (userStore.isSuperAdmin) return '超级管理员'
  return roles.length ? roles.join(' / ') : '管理员'
})

/**
 * 后端 SUM/COUNT 的返回值可能是数字、字符串或 null
 * （MySQL 的 DECIMAL 会被序列化成字符串）。
 * 这里统一转成数字再展示，避免页面上出现 "null" 或 "2.20"。
 */
function toNumber(v: number | string | null | undefined): number {
  if (v === null || v === undefined || v === '') return 0
  const n = typeof v === 'string' ? Number(v) : v
  return Number.isFinite(n) ? n : 0
}

/** 保留一位小数，整数则不显示小数点 */
function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

const stats = computed(() => {
  const o = overview.value
  return [
    { label: '用户', value: String(toNumber(o.userCount)) },
    { label: '订单', value: String(toNumber(o.orderCount)) },
    { label: '回收(kg)', value: fmt(toNumber(o.recycleWeight)) },
    { label: '碳减排(kg)', value: fmt(toNumber(o.carbonSaved)) },
    { label: '发放积分', value: String(toNumber(o.pointsIssued)) },
    { label: '商品', value: String(toNumber(o.productCount)) }
  ]
})

/**
 * 待办数量。
 *
 * 数据来源是「订单状态分布」接口（按 orderType + status 分组），
 * 直接复用，不额外加一个"待办统计"接口 —— 少一个接口就少一处不一致。
 *
 * 状态码含义（见后端 OrderStatusEnum）：
 *   10 待审核 / 11 待回收 / 12 待结算 —— 回收单
 *   41 待发货                       —— 采购单
 */
const TODO_SPEC = [
  { key: 'recycleAudit', label: '待审核', orderType: 1, status: 10 },
  { key: 'recycleSettle', label: '待结算', orderType: 1, status: 12 },
  { key: 'purchaseDeliver', label: '待发货', orderType: 2, status: 41 }
] as const

const todos = computed(() =>
  TODO_SPEC.map((spec) => {
    const row = statusRows.value.find(
      (r) => Number(r.orderType) === spec.orderType && Number(r.status) === spec.status
    )
    return {
      key: spec.key,
      label: spec.label,
      count: toNumber(row?.total),
      orderType: spec.orderType,
      status: spec.status
    }
  })
)

function goTodo(item: { orderType: number; status: number }) {
  router.push(`/m/orders?type=${item.orderType}&status=${item.status}`)
}

onMounted(async () => {
  /*
   * 三个接口互不依赖，用 allSettled 并发拉。
   *
   * 为什么不是 Promise.all：任何一个接口失败都会让整页数据全空 ——
   * 而看板上"总览成功、热门商品失败"是完全可接受的降级。
   * allSettled 让每个区块独立成败，失败的那块自然显示为空。
   */
  const [ov, st, tp] = await Promise.allSettled([
    getDashboardOverview(),
    getOrderStatus(),
    getTopProduct(5)
  ])

  if (ov.status === 'fulfilled') overview.value = ov.value ?? {}
  if (st.status === 'fulfilled') statusRows.value = st.value ?? []
  if (tp.status === 'fulfilled') topProducts.value = tp.value ?? []

  loadingTodo.value = false
})
</script>

<style scoped>
/* ---------------- 个人信息条 ---------------- */
.hero {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 16px;
  background: linear-gradient(135deg, #409eff, #6fb4ff);
  color: #fff;
}

.hero__avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.25);
}

.hero__name {
  font-size: 17px;
  font-weight: 600;
}

.hero__role {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.85;
}

/* ---------------- 待办 ---------------- */
.todo__hint {
  font-size: 12px;
  color: var(--m-text-3);
}

.todo__loading {
  padding: 12px 0;
  font-size: 13px;
  color: var(--m-text-3);
}

.todo {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.todo__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 4px;
  border: none;
  border-radius: 10px;
  background: #f7f9fc;
  cursor: pointer;
}

.todo__item--zero {
  background: #fafbfc;
}

.todo__count {
  font-size: 22px;
  font-weight: 700;
  color: var(--m-primary);
  line-height: 1.1;
}

.todo__item--zero .todo__count {
  color: var(--m-text-3);
}

.todo__label {
  font-size: 12px;
  color: var(--m-text-2);
}

/* ---------------- 总览 ---------------- */
.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px 8px;
}

.stats__item {
  text-align: center;
}

.stats__value {
  font-size: 18px;
  font-weight: 600;
  color: var(--m-text);
}

.stats__label {
  margin-top: 2px;
  font-size: 11px;
  color: var(--m-text-3);
}

/* ---------------- 快捷操作 ---------------- */
.quick {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.quick__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--m-line);
  border-radius: 10px;
  background: #fff;
  font-size: 14px;
  color: var(--m-text);
  cursor: pointer;
}

.quick__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
}

.quick__icon--primary {
  color: var(--m-primary);
  background: rgba(64, 158, 255, 0.12);
}

.quick__icon--warning {
  color: var(--m-warning);
  background: rgba(250, 140, 22, 0.12);
}

.quick__icon--success {
  color: var(--m-success);
  background: rgba(82, 196, 26, 0.12);
}

.quick__icon--info {
  color: var(--m-text-2);
  background: #f2f3f5;
}

/* ---------------- 排行 ---------------- */
.rank {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid var(--m-line);
  font-size: 14px;
}

.rank:last-child {
  border-bottom: none;
}

.rank__no {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--m-text-3);
  background: #f2f3f5;
}

.rank__no--top {
  color: #fff;
  background: var(--m-warning);
}

.rank__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--m-text);
}

.rank__count {
  color: var(--m-text-3);
  font-size: 13px;
}

.footnote {
  margin: 8px 0 24px;
  text-align: center;
  font-size: 11px;
  color: var(--m-text-3);
}
</style>
