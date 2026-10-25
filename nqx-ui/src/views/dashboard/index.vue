<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  getDashboardOverview,
  getOrderStatus,
  getPointsTrend,
  getRecycleTrend,
  getTopProduct,
  type DashboardOverview,
  type OrderStatusStat,
  type PointsTrendItem,
  type RecycleTrendItem,
  type TopProductItem
} from '@/api/dashboard'

/**
 * 数据看板。
 *
 * 五个接口全部来自 AdminDashboardController，路径与 @/api/dashboard 一一对应，
 * 鉴权由 request 拦截器统一加 token，这里只管取数与渲染。
 *
 * 本页的取数与渲染策略是围绕「数据随时可能被清空」这一个前提定的：
 * 1. 数字一律经 toNumber / formatNumber 收口 —— 后端 SUM 在空表上返回 null、
 *    DECIMAL 又序列化成字符串（"1.20"），不收口就会渲染出 NaN 或空白；
 * 2. 趋势图自己按天补齐日期轴 —— 后端只返回「有数据的那几天」，
 *    空库时返回空数组，若直接拿返回数据当坐标轴，图上就是一片白，
 *    补齐后画出的是一条贴底的 0 值线，信息的完整度反而更高；
 * 3. 分布类图表（订单状态、热门商品）没有类目就真的没东西可画，
 *    用 el-empty 覆盖显示「暂无数据」，而不是留一块空白画布。
 */

/* ------------------------------ 状态 ------------------------------ */

/** 时间范围档位。后端 normalizeDays 上限 180 天，这里只给三个常用档 */
const rangeOptions = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 }
]

/** 热门商品条数。后端把 limit 夹在 [1, 50] */
const TOP_PRODUCT_LIMIT = 10

const days = ref(30)
const loading = ref(false)

const overview = ref<DashboardOverview>({})
const recycleTrend = ref<RecycleTrendItem[]>([])
const pointsTrend = ref<PointsTrendItem[]>([])
const orderStatus = ref<OrderStatusStat[]>([])
const topProducts = ref<TopProductItem[]>([])

/**
 * 请求序号。
 * 连点刷新或快速切换时间范围时，先发出的请求可能后返回，
 * 用序号把过期响应丢掉，避免图表被旧数据覆盖（比如切到 7 天后又被 30 天的结果盖回去）。
 */
let requestSeq = 0

/* ------------------------------ 格式化 ------------------------------ */

/**
 * 收口所有后端数字。
 * null / undefined / '' / 非数字字符串一律当 0：看板宁可显示 0，
 * 也不能出现 NaN 或者一个空白的格子。
 */
function toNumber(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** 千分位格式化，digits 控制小数位（重量类指标固定两位） */
function formatNumber(value: unknown, digits = 0): string {
  return toNumber(value).toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  })
}

/** 本地时区的 yyyy-MM-dd。不用 toISOString：它按 UTC 算，东八区跨零点会差一天 */
function toYmd(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** 把后端日期统一成 yyyy-MM-dd；格式对不上就丢掉，避免脏数据污染坐标轴 */
function normalizeDate(value: unknown): string {
  if (typeof value !== 'string') return ''
  const day = value.slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(day) ? day : ''
}

/**
 * 构造完整日期轴：以浏览器本地「今天」为终点倒推 days 天，
 * 再把后端返回的日期并进来。
 *
 * 并入后端日期是为了跨时区容错 —— 服务端与浏览器不在同一时区时，
 * 边界那一天可能落在本地倒推范围之外，只靠本地生成会把这条数据静默丢掉。
 */
function buildDateAxis(dates: unknown[]): string[] {
  const set = new Set<string>()
  for (const value of dates) {
    const day = normalizeDate(value)
    if (day) set.add(day)
  }
  const today = new Date()
  for (let i = days.value - 1; i >= 0; i--) {
    set.add(toYmd(new Date(today.getFullYear(), today.getMonth(), today.getDate() - i)))
  }
  // yyyy-MM-dd 的字典序与时间先后一致，直接 sort 即可按时间排列
  return [...set].sort()
}

/** 按日期取值的查表，缺日期即 0 —— 趋势线的「没有数据」就该是 0 而不是断点 */
function pickByDate(map: Map<string, number>, day: string): number {
  return map.get(day) ?? 0
}

/** tooltip 里要拼 HTML，商品名来自后台录入，不转义会有注入风险 */
const HTML_ESCAPE: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => HTML_ESCAPE[ch] ?? ch)
}

/* ------------------------------ 统计卡片 ------------------------------ */

/**
 * 卡片用 computed 而不是在取数后赋值：
 * 这样「接口失败 / 首次加载中」时 overview 仍是 {}，卡片自动显示 0，
 * 不需要额外写一套兜底文案。
 */
const statCards = computed(() => [
  {
    label: '用户数',
    value: formatNumber(overview.value.userCount),
    unit: '人',
    color: '#409eff',
    icon: 'User'
  },
  {
    label: '订单总数',
    value: formatNumber(overview.value.orderCount),
    unit: '单',
    color: '#909399',
    icon: 'Tickets'
  },
  {
    label: '回收总重',
    value: formatNumber(overview.value.recycleWeight, 2),
    unit: 'kg',
    color: '#67c23a',
    icon: 'ScaleToOriginal'
  },
  {
    label: '碳减排',
    value: formatNumber(overview.value.carbonSaved, 2),
    unit: 'kg',
    color: '#f56c6c',
    icon: 'Sunny'
  },
  {
    label: '已发放积分',
    value: formatNumber(overview.value.pointsIssued),
    unit: '分',
    color: '#e6a23c',
    icon: 'Coin'
  },
  {
    label: '商品数',
    value: formatNumber(overview.value.productCount),
    unit: '件',
    color: '#b37feb',
    icon: 'Goods'
  }
])

/* ------------------------------ 图表实例 ------------------------------ */

// 用 shallowRef：ECharts 实例内部结构庞大，交给 Vue 做深度响应式纯属浪费性能
const recycleRef = ref<HTMLDivElement>()
const pointsRef = ref<HTMLDivElement>()
const statusRef = ref<HTMLDivElement>()
const productRef = ref<HTMLDivElement>()

const recycleChart = shallowRef<echarts.ECharts>()
const pointsChart = shallowRef<echarts.ECharts>()
const statusChart = shallowRef<echarts.ECharts>()
const productChart = shallowRef<echarts.ECharts>()

/** 图表是否有数据，用来决定显示「暂无数据」还是画布 */
const recycleEmpty = computed(() => recycleTrend.value.length === 0)
const pointsEmpty = computed(() => pointsTrend.value.length === 0)
const statusEmpty = computed(() => orderStatus.value.length === 0)
const productEmpty = computed(() => topProducts.value.length === 0)

/* ------------------------------ 图表配置 ------------------------------ */

function buildRecycleOption(): echarts.EChartsOption {
  const weightByDay = new Map<string, number>()
  const countByDay = new Map<string, number>()
  for (const row of recycleTrend.value) {
    const day = normalizeDate(row.statDate)
    if (!day) continue
    weightByDay.set(day, toNumber(row.totalWeight))
    countByDay.set(day, toNumber(row.orderCount))
  }

  const axis = buildDateAxis(recycleTrend.value.map((row) => row.statDate))

  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['回收重量', '回收单量'], top: 0 },
    grid: { left: 55, right: 55, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      // 只显示 MM-DD：90 天档位下完整日期会挤成一团，年份看范围选择器就够了。
      // 刻度间隔交给 ECharts 的 auto 策略（它按标签宽度算），并强制显示最后一个日期 ——
      // auto 只按固定间隔取整点时会把末尾那天吞掉，而「统计到今天」恰恰是使用者要确认的信息
      data: axis.map((day) => day.slice(5)),
      axisLabel: { showMaxLabel: true }
    },
    // 重量与单量量纲不同（kg 可能是小数、单量是整数），共用一根轴会让小的那条压成直线
    yAxis: [
      { type: 'value', name: '重量(kg)' },
      // 单量必须是整数刻度，否则会出现 0.5 单这种没有意义的刻度
      { type: 'value', name: '单量(单)', minInterval: 1, splitLine: { show: false } }
    ],
    series: [
      {
        name: '回收重量',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        data: axis.map((day) => pickByDate(weightByDay, day)),
        itemStyle: { color: '#67c23a' },
        areaStyle: { opacity: 0.15 }
      },
      {
        name: '回收单量',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        yAxisIndex: 1,
        data: axis.map((day) => pickByDate(countByDay, day)),
        itemStyle: { color: '#409eff' },
        lineStyle: { type: 'dashed' }
      }
    ]
  }
}

function buildPointsOption(): echarts.EChartsOption {
  const earnByDay = new Map<string, number>()
  const spendByDay = new Map<string, number>()
  for (const row of pointsTrend.value) {
    const day = normalizeDate(row.statDate)
    if (!day) continue
    earnByDay.set(day, toNumber(row.earn))
    spendByDay.set(day, toNumber(row.spend))
  }

  const axis = buildDateAxis(pointsTrend.value.map((row) => row.statDate))

  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['发放积分', '消耗积分'], top: 0 },
    grid: { left: 55, right: 30, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: axis.map((day) => day.slice(5)),
      // 同上：保证末尾那天一定有刻度，能一眼看出数据统计到哪一天
      axisLabel: { showMaxLabel: true }
    },
    yAxis: { type: 'value', name: '积分' },
    series: [
      {
        name: '发放积分',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        data: axis.map((day) => pickByDate(earnByDay, day)),
        itemStyle: { color: '#67c23a' },
        areaStyle: { opacity: 0.15 }
      },
      {
        name: '消耗积分',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        data: axis.map((day) => pickByDate(spendByDay, day)),
        itemStyle: { color: '#f56c6c' },
        areaStyle: { opacity: 0.15 }
      }
    ]
  }
}

function buildStatusOption(): echarts.EChartsOption {
  // 同一 statusText 在回收单与采购单下各有一行，先按 statusText 汇总，
  // 再用堆叠把订单类型分开：直接摊平会看不出「这个状态属于哪类单」
  const groups = new Map<string, { recycle: number; purchase: number; total: number }>()
  for (const row of orderStatus.value) {
    // statusText 由后端枚举给出，缺失时才退化成「未知」
    const name = row.statusText || '未知'
    const item = groups.get(name) ?? { recycle: 0, purchase: 0, total: 0 }
    const total = toNumber(row.total)
    if (toNumber(row.orderType) === 1) {
      item.recycle += total
    } else {
      item.purchase += total
    }
    item.total += total
    groups.set(name, item)
  }

  // 横向条形图的类目轴自下而上绘制，升序排列才能让单量最多的状态出现在最上方
  const rows = [...groups.entries()].sort((a, b) => a[1].total - b[1].total)

  // 某一类订单一条数据都没有时不生成对应 series，免得图例里挂一条永远为 0 的线
  const hasRecycle = rows.some(([, value]) => value.recycle > 0)
  const hasPurchase = rows.some(([, value]) => value.purchase > 0)

  const series: echarts.SeriesOption[] = []
  if (hasRecycle) {
    series.push({
      name: '回收单',
      type: 'bar',
      stack: 'orderType',
      barWidth: 16,
      data: rows.map(([, value]) => value.recycle),
      itemStyle: { color: '#409eff' }
    })
  }
  if (hasPurchase) {
    series.push({
      name: '采购单',
      type: 'bar',
      stack: 'orderType',
      barWidth: 16,
      data: rows.map(([, value]) => value.purchase),
      // 圆角只加在最后一段（堆叠的右端），中间接缝保持直角
      itemStyle: { color: '#67c23a', borderRadius: [0, 4, 4, 0] }
    })
  }

  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['回收单', '采购单'], top: 0 },
    grid: { left: 70, right: 30, top: 40, bottom: 30 },
    xAxis: { type: 'value', name: '单量', minInterval: 1 },
    yAxis: { type: 'category', data: rows.map(([name]) => name) },
    series
  }
}

function buildProductOption(): echarts.EChartsOption {
  // 同样升序：横向条形图自上而下按销量递减更符合阅读习惯
  const rows = [...topProducts.value].sort(
    (a, b) => toNumber(a.totalQuantity) - toNumber(b.totalQuantity)
  )

  return {
    tooltip: {
      trigger: 'item',
      // 后端同时给了「下单笔数」和「下单件数」，条形只画得下件数，笔数放进 tooltip
      formatter: (params) => {
        const item = Array.isArray(params) ? params[0] : params
        if (!item) return ''
        const row = rows[item.dataIndex]
        const lines = [`<strong>${escapeHtml(String(item.name))}</strong>`]
        lines.push(`下单件数：${formatNumber(item.value)}`)
        lines.push(`下单笔数：${formatNumber(row?.orderCount)}`)
        return lines.join('<br/>')
      }
    },
    grid: { left: 150, right: 40, top: 20, bottom: 30 },
    xAxis: { type: 'value', name: '件数', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: rows.map((row) => row.productName || '未命名商品'),
      // 商品名普遍偏长，截断显示，完整名称在 tooltip 里给出
      axisLabel: { width: 130, overflow: 'truncate' }
    },
    series: [
      {
        name: '下单件数',
        type: 'bar',
        barWidth: 14,
        data: rows.map((row) => toNumber(row.totalQuantity)),
        itemStyle: { color: '#e6a23c', borderRadius: [0, 4, 4, 0] }
      }
    ]
  }
}

/* ------------------------------ 渲染 ------------------------------ */

/**
 * 统一用 setOption(option, true)（notMerge）：
 * 订单状态图的 series 数量会随数据类型变化，合并模式会残留上一次的 series。
 */
function renderCharts() {
  recycleChart.value?.setOption(buildRecycleOption(), true)
  pointsChart.value?.setOption(buildPointsOption(), true)
  statusChart.value?.setOption(buildStatusOption(), true)
  productChart.value?.setOption(buildProductOption(), true)
}

/* ------------------------------ 取数 ------------------------------ */

/** 后端在某些情况下可能返回 null，统一兜底成空数组，避免后续 map 抛错 */
function toArray<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : []
}

async function loadAll() {
  const seq = ++requestSeq
  loading.value = true

  // 用 allSettled 而不是 all：一个接口挂掉不该让整页数字全部归零，
  // 各块的失败互不影响，能显示的先显示出来
  const results = await Promise.allSettled([
    getDashboardOverview(),
    getRecycleTrend(days.value),
    getPointsTrend(days.value),
    getOrderStatus(),
    getTopProduct(TOP_PRODUCT_LIMIT)
  ])

  // 已经有更新的请求发出去了，这次的结果直接丢弃（loading 交给新请求收尾）
  if (seq !== requestSeq) return
  loading.value = false

  const [overviewRes, recycleRes, pointsRes, statusRes, productRes] = results
  const failed: string[] = []

  if (overviewRes.status === 'fulfilled') {
    overview.value = overviewRes.value ?? {}
  } else {
    failed.push('总览指标')
  }

  if (recycleRes.status === 'fulfilled') {
    recycleTrend.value = toArray(recycleRes.value)
  } else {
    failed.push('回收量趋势')
  }

  if (pointsRes.status === 'fulfilled') {
    pointsTrend.value = toArray(pointsRes.value)
  } else {
    failed.push('积分趋势')
  }

  if (statusRes.status === 'fulfilled') {
    orderStatus.value = toArray(statusRes.value)
  } else {
    failed.push('订单状态分布')
  }

  if (productRes.status === 'fulfilled') {
    topProducts.value = toArray(productRes.value)
  } else {
    failed.push('热门商品')
  }

  // 失败明细（HTTP 状态、后端 msg）已由 request 拦截器逐条弹出，
  // 这里只补一条汇总，告诉使用者「页面上哪几块没能刷新」
  if (failed.length > 0) {
    if (failed.length === results.length) {
      ElMessage.error('看板数据加载失败，请稍后重试')
    } else {
      ElMessage.warning(`部分数据加载失败：${failed.join('、')}，对应区域仍是上一次的结果`)
    }
  }

  renderCharts()
}

/* ------------------------------ 生命周期 ------------------------------ */

/**
 * 窗口尺寸变化时重算图表尺寸。
 *
 * ECharts 不会自动跟随容器变化，监听 resize 是必须的，
 * 否则用户折叠侧边栏后图表会保持旧宽度、右侧留白。
 */
function handleResize() {
  recycleChart.value?.resize()
  pointsChart.value?.resize()
  statusChart.value?.resize()
  productChart.value?.resize()
}

onMounted(() => {
  // 先建实例再取数：init 依赖 DOM 已挂载，而数据是异步的，
  // 放在同一个 onMounted 里就能保证「有容器 → 建实例 → 填数据」的顺序
  if (recycleRef.value) recycleChart.value = echarts.init(recycleRef.value)
  if (pointsRef.value) pointsChart.value = echarts.init(pointsRef.value)
  if (statusRef.value) statusChart.value = echarts.init(statusRef.value)
  if (productRef.value) productChart.value = echarts.init(productRef.value)

  window.addEventListener('resize', handleResize)
  loadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  // 不 dispose 会保留 ECharts 内部注册的事件与定时器，反复进出页面即内存泄漏
  recycleChart.value?.dispose()
  pointsChart.value?.dispose()
  statusChart.value?.dispose()
  productChart.value?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="page-container">
    <div class="page-toolbar">
      <span class="page-title">数据看板</span>
      <div class="toolbar-actions">
        <el-radio-group v-model="days" @change="loadAll">
          <el-radio-button v-for="opt in rangeOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>
        <el-button type="primary" :icon="'Refresh'" :loading="loading" @click="loadAll">
          刷新
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col v-for="card in statCards" :key="card.label" :xs="12" :sm="8" :md="8" :lg="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-card-body">
            <el-icon class="stat-icon" :style="{ color: card.color }">
              <component :is="card.icon" />
            </el-icon>
            <div class="stat-text">
              <div class="stat-label">{{ card.label }}</div>
              <div class="stat-value" :style="{ color: card.color }">
                {{ card.value }}
                <span class="stat-unit">{{ card.unit }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="24" :lg="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span class="card-title">回收量趋势</span>
              <el-tag v-if="recycleEmpty && !loading" size="small" type="info" effect="plain">
                所选范围内暂无数据
              </el-tag>
            </div>
          </template>
          <div ref="recycleRef" class="chart chart-line"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="24" :lg="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span class="card-title">积分趋势</span>
              <el-tag v-if="pointsEmpty && !loading" size="small" type="info" effect="plain">
                所选范围内暂无数据
              </el-tag>
            </div>
          </template>
          <div ref="pointsRef" class="chart chart-line"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="24" :lg="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span class="card-title">订单状态分布</span>
              <el-tag v-if="statusEmpty && !loading" size="small" type="info" effect="plain">
                暂无数据
              </el-tag>
            </div>
          </template>
          <div class="chart-box">
            <div ref="statusRef" class="chart chart-bar"></div>
            <!-- 没有类目时条形图只剩空坐标轴，用空状态盖住，比留一块白布清楚 -->
            <div v-if="statusEmpty && !loading" class="chart-empty">
              <el-empty description="暂无数据" :image-size="70" />
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="24" :lg="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span class="card-title">热门商品 Top {{ TOP_PRODUCT_LIMIT }}</span>
              <el-tag v-if="productEmpty && !loading" size="small" type="info" effect="plain">
                暂无数据
              </el-tag>
            </div>
          </template>
          <div class="chart-box">
            <div ref="productRef" class="chart chart-bar"></div>
            <div v-if="productEmpty && !loading" class="chart-empty">
              <el-empty description="暂无数据" :image-size="70" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.page-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stat-card {
  margin-bottom: 16px;
}

.stat-card-body {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  font-size: 36px;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  margin-top: 4px;
  /* 数字位数变化时不换行，卡片高度保持一致 */
  white-space: nowrap;
}

.stat-unit {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
  margin-left: 2px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.chart {
  width: 100%;
}

.chart-line {
  height: 320px;
}

.chart-bar {
  height: 300px;
}

/* 空状态覆盖层要贴住图表容器，故这里必须相对定位 */
.chart-box {
  position: relative;
}

.chart-empty {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
}

.chart-row {
  margin-top: 0;
}
</style>
