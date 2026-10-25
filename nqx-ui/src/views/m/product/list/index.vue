<template>
  <div class="m-page">
    <!-- ================= 搜索 ================= -->
    <div class="search">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称"
        clearable
        size="large"
        @keyup.enter="reload"
        @clear="reload"
      >
        <template #prefix>
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="#8f959e" stroke-width="2">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" stroke-linecap="round" />
          </svg>
        </template>
      </el-input>
    </div>

    <!-- ================= 状态筛选 ================= -->
    <div class="m-segmented">
      <button
        v-for="opt in STATUS_OPTIONS"
        :key="opt.label"
        class="m-segmented__item"
        :class="{ active: statusFilter === opt.value }"
        @click="switchStatus(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>

    <!-- ================= 列表 ================= -->
    <div v-if="loading && list.length === 0" class="m-empty">加载中…</div>

    <template v-else>
      <div
        v-for="p in list"
        :key="p.id"
        class="m-card m-card--tap product"
        @click="openEdit(p.id)"
      >
        <div class="product__main">
          <div class="product__cover">
            <img v-if="p.coverUrl" :src="p.coverUrl" alt="" @error="onImgError" />
            <span v-else class="product__cover-empty">无图</span>
          </div>

          <div class="product__info">
            <div class="product__name">{{ p.name }}</div>
            <div class="product__sub" v-if="p.subTitle">{{ p.subTitle }}</div>

            <div class="product__meta">
              <span class="product__price">¥{{ centsToYuan(p.price) }}</span>
              <span class="product__unit">/ {{ p.unit }}</span>
            </div>

            <div class="product__stats">
              <span>库存 {{ p.stock }}</span>
              <span>销量 {{ p.soldCount ?? 0 }}</span>
            </div>
          </div>
        </div>

        <!--
          上架/下架开关。
          这是手机上最高频的商品操作，所以直接放在卡片上，
          而不是藏进详情页 —— 一次点击就完成，不用进进出出。
        -->
        <div class="product__foot" @click.stop>
          <span class="product__status" :class="p.status === 1 ? 'on' : 'off'">
            {{ p.status === 1 ? '已上架' : '已下架' }}
          </span>
          <el-switch
            :model-value="p.status === 1"
            :loading="switchingId === p.id"
            @change="(val: string | number | boolean) => toggleStatus(p, Boolean(val))"
          />
        </div>
      </div>

      <div v-if="list.length === 0" class="m-empty">
        没有符合条件的商品
      </div>

      <div v-if="list.length > 0" class="more">
        <el-button v-if="hasMore" :loading="loading" text @click="loadMore">
          加载更多
        </el-button>
        <span v-else class="more__end">没有更多了</span>
      </div>
    </template>

    <!-- 悬浮新增 -->
    <button class="m-fab" aria-label="新增商品" @click="openEdit()">＋</button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changeProductStatus, pageProduct } from '@/api/product'
import type { ProductVO } from '@/types/product'

/**
 * 移动端商品管理。
 *
 * 与桌面版的核心差别：**这里没有表格**。
 *
 * 桌面版用 el-table 展示 8 列（名称/分类/规格/库存/销量/价格/状态/操作），
 * 手机上那套结构完全没法用 —— 列挤在一起看不清，或者要左右拖动。
 * 所以每条商品做成一张卡片，只保留购买决策最关键的 4 个信息：
 * 图片、名称、价格、库存，外加最常用的「上架/下架」开关。
 *
 * 想改分类、副标题这些次要字段，点卡片进编辑页。
 */

const router = useRouter()

const PAGE_SIZE = 10

const list = ref<ProductVO[]>([])
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref<number | undefined>(undefined)
const pageNum = ref(1)
const total = ref(0)
const switchingId = ref<number | null>(null)

const STATUS_OPTIONS = [
  { label: '全部', value: undefined as number | undefined },
  { label: '已上架', value: 1 },
  { label: '已下架', value: 0 }
] as const

const hasMore = computed(() => list.value.length < total.value)

/** 分 → 元。后端全链路用分，展示时换算 */
function centsToYuan(cents?: number): string {
  if (cents === undefined || cents === null) return '0.00'
  return (cents / 100).toFixed(2)
}

/** 图片加载失败时清掉地址，回落到"无图"占位，避免出现破图图标 */
function onImgError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

async function fetchPage(reset: boolean) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      pageNum.value = 1
    }
    const res = await pageProduct({
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
      name: keyword.value.trim() || undefined,
      status: statusFilter.value
    })
    const records = res.records ?? []
    list.value = reset ? records : [...list.value, ...records]
    total.value = Number(res.total ?? 0)
  } catch (error) {
    console.error('[移动端商品] 列表加载失败', error)
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

function switchStatus(value: number | undefined) {
  if (statusFilter.value === value) return
  statusFilter.value = value
  // 换筛选条件必须回到第一页，否则会接着上一次的页码往后翻，出现空页
  list.value = []
  total.value = 0
  reload()
}

/**
 * 上架 / 下架。
 *
 * 先在本地乐观更新，接口失败再回滚 —— 手机上等一次网络往返的体感很差，
 * 而"开关点了没反应"更是不能接受。
 * 失败回滚保证了：界面最终一定与服务器一致。
 */
async function toggleStatus(product: ProductVO, on: boolean) {
  const previous = product.status
  const next = on ? 1 : 0
  switchingId.value = product.id
  product.status = next

  try {
    await changeProductStatus(product.id, next)
    ElMessage.success(next === 1 ? '已上架' : '已下架')
  } catch (error) {
    console.error('[移动端商品] 上下架失败', error)
    product.status = previous
    ElMessage.error('操作失败，请重试')
  } finally {
    switchingId.value = null
  }
}

function openEdit(id?: number) {
  router.push(id ? `/m/product/edit/${id}` : '/m/product/edit')
}

onMounted(reload)
</script>

<style scoped>
.search {
  padding: 12px 12px 0;
}

/* ---------------- 商品卡片 ---------------- */
.product {
  padding: 12px;
}

.product__main {
  display: flex;
  gap: 12px;
}

.product__cover {
  flex: 0 0 64px;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  background: #f2f3f5;
  display: flex;
  align-items: center;
  justify-content: center;
}

.product__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product__cover-empty {
  font-size: 11px;
  color: var(--m-text-3);
}

.product__info {
  flex: 1;
  min-width: 0;
}

.product__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--m-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product__sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--m-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product__meta {
  margin-top: 6px;
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.product__price {
  font-size: 16px;
  font-weight: 600;
  color: var(--m-danger);
}

.product__unit {
  font-size: 12px;
  color: var(--m-text-3);
}

.product__stats {
  margin-top: 4px;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--m-text-3);
}

.product__foot {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--m-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.product__status {
  font-size: 13px;
}

.product__status.on {
  color: var(--m-success);
}

.product__status.off {
  color: var(--m-text-3);
}

/* ---------------- 加载更多 ---------------- */
.more {
  padding: 4px 0 20px;
  text-align: center;
}

.more__end {
  font-size: 12px;
  color: var(--m-text-3);
}
</style>
