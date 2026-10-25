<template>
  <div class="m-page m-page--with-bar">
    <!-- ================= 步骤条 ================= -->
    <div class="steps">
      <div
        v-for="(s, i) in STEPS"
        :key="s.key"
        class="steps__item"
        :class="{ active: step === i, done: step > i }"
      >
        <span class="steps__dot">{{ step > i ? '✓' : i + 1 }}</span>
        <span class="steps__label">{{ s.label }}</span>
      </div>
    </div>

    <div v-if="loading" class="m-empty">加载中…</div>

    <template v-else>
      <!-- ================= 第 1 步：基本信息 ================= -->
      <section v-show="step === 0" class="m-card">
        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>商品名称</label>
          <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="例如：CR2032 锂锰纽扣电池" />
        </div>

        <div class="m-field">
          <label class="m-field__label">副标题</label>
          <el-input v-model="form.subTitle" maxlength="200" placeholder="选填，一句话卖点" />
        </div>

        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>所属分类</label>
          <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option
              v-for="c in categoryOptions"
              :key="c.id"
              :label="c.label"
              :value="c.id"
            />
          </el-select>
        </div>

        <div class="m-field">
          <label class="m-field__label">规格</label>
          <el-input v-model="form.spec" maxlength="100" placeholder="例如：直径 20mm / 3V" />
        </div>

        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>计量单位</label>
          <el-input v-model="form.unit" maxlength="20" placeholder="枚 / 盒 / 个" />
        </div>

        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>商品类型</label>
          <el-select v-model="form.productType" placeholder="请选择" style="width: 100%">
            <el-option label="采购商品（花钱买）" :value="1" />
            <el-option label="积分兑换商品（积分换）" :value="2" />
            <el-option label="两者皆可" :value="3" />
          </el-select>
        </div>
      </section>

      <!-- ================= 第 2 步：价格与库存 ================= -->
      <section v-show="step === 1" class="m-card">
        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>单价（元）</label>
          <el-input v-model="form.priceYuan" type="number" inputmode="decimal" placeholder="0.00">
            <template #prefix>¥</template>
          </el-input>
          <p class="tip">后端按「分」存储，保存时自动换算（{{ centsHint }}）</p>
        </div>

        <div class="m-field">
          <label class="m-field__label">积分价</label>
          <el-input v-model="form.pointsPriceText" type="number" inputmode="numeric" placeholder="兑换所需积分，0 表示不参与兑换" />
        </div>

        <div class="m-field">
          <label class="m-field__label"><span class="req">*</span>库存</label>
          <el-input v-model="form.stockText" type="number" inputmode="numeric" placeholder="0" />
        </div>

        <div class="m-field">
          <label class="m-field__label">每人限兑数量</label>
          <el-input v-model="form.exchangeLimitText" type="number" inputmode="numeric" placeholder="0 表示不限" />
        </div>
      </section>

      <!-- ================= 第 3 步：图片与描述 ================= -->
      <section v-show="step === 2" class="m-card">
        <div class="m-field">
          <label class="m-field__label">封面图</label>
          <div class="cover">
            <img v-if="form.coverUrl" :src="form.coverUrl" alt="" class="cover__img" />
            <div v-else class="cover__empty">未设置</div>
            <div class="cover__ops">
              <button class="mini" :disabled="uploading" @click="pickCover">
                {{ uploading ? '上传中…' : '上传图片' }}
              </button>
              <button v-if="form.coverUrl" class="mini mini--danger" @click="form.coverUrl = ''">
                清除
              </button>
            </div>
          </div>
          <!-- 支持直接粘贴图片地址：有些图已经在图床上，没必要再传一遍 -->
          <el-input v-model="form.coverUrl" placeholder="或直接粘贴图片链接" clearable />
        </div>

        <div class="m-field">
          <label class="m-field__label">轮播图</label>
          <div class="gallery">
            <div v-for="(img, i) in form.images" :key="i" class="gallery__item">
              <img :src="img" alt="" />
              <button class="gallery__del" @click="form.images.splice(i, 1)">×</button>
            </div>
            <button class="gallery__add" :disabled="uploading" @click="pickGallery">＋</button>
          </div>
          <div class="gallery__add-url">
            <el-input v-model="galleryUrl" placeholder="粘贴图片链接后点添加" clearable />
            <el-button :disabled="!galleryUrl.trim()" @click="addGalleryUrl">添加</el-button>
          </div>
        </div>

        <div class="m-field">
          <label class="m-field__label">商品描述</label>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="5"
            placeholder="详细介绍、使用说明、注意事项…"
          />
        </div>
      </section>

      <!-- ================= 第 4 步：更多设置 ================= -->
      <section v-show="step === 3" class="m-card">
        <div class="m-card__head">
          <span class="m-card__title">更多设置</span>
          <span class="tip">不改也能保存</span>
        </div>

        <div class="switch-row">
          <span>是否热门</span>
          <el-switch v-model="form.isHot" :active-value="1" :inactive-value="0" />
        </div>

        <div class="switch-row">
          <span>上架状态</span>
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </div>

        <div class="m-field" style="margin-top: 12px">
          <label class="m-field__label">排序值（越大越靠前）</label>
          <el-input v-model="form.sortText" type="number" inputmode="numeric" placeholder="0" />
        </div>

        <el-alert
          v-if="isEdit"
          type="info"
          :closable="false"
          show-icon
          title="保存后立即生效，小程序端会看到最新信息"
        />
      </section>
    </template>

    <!-- ================= 底部按钮 ================= -->
    <div class="m-bar">
      <el-button v-if="step > 0" @click="step -= 1">上一步</el-button>
      <el-button v-if="step < STEPS.length - 1" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="saving" @click="save">
        {{ isEdit ? '保存修改' : '创建商品' }}
      </el-button>
    </div>

    <!-- 隐藏的文件选择器：手机点它会直接调起相册/相机 -->
    <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverPicked" />
    <input ref="galleryInput" type="file" accept="image/*" hidden @change="onGalleryPicked" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createProduct, getProduct, getCategoryTree, updateProduct } from '@/api/product'
import { uploadFile } from '@/api/file'
import type { CategoryVO, ProductSaveDTO } from '@/types/product'

/**
 * 移动端商品编辑 —— 分步表单。
 *
 * <h2>为什么不能把桌面版的弹窗直接搬过来</h2>
 *
 * 桌面版是一个 600px 宽的弹窗里塞 16 个字段，两列布局。手机上照搬的结果是：
 * 单列排列、需要滚动好几屏、键盘弹起后字段被挡住、保存按钮找不到。
 *
 * 商家类 App 的通行解法是**分步**：
 *   ① 基本信息（6 个字段）→ ② 价格库存（4 个）→ ③ 图片描述（3 个）→ ④ 更多（3 个）
 *
 * 每一步只有一屏，底部固定「上一步 / 下一步」，拇指够得着。
 * 字段按"发布一个商品时的思考顺序"排：先想清楚是什么，再定价，最后配图。
 *
 * <h2>价格单位的坑</h2>
 *
 * 后端全链路用「分」，管理员习惯按「元」录入。所以表单里存「元」字符串，
 * 提交时才乘 100 取整。反过来做（表单存分）会让输入框里显示 1200，
 * 管理员以为是 1200 元。
 */

const route = useRoute()
const router = useRouter()

const STEPS = [
  { key: 'basic', label: '基本信息' },
  { key: 'price', label: '价格库存' },
  { key: 'media', label: '图片描述' },
  { key: 'extra', label: '更多' }
] as const

const step = ref(0)
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)

const coverInput = ref<HTMLInputElement | null>(null)
const galleryInput = ref<HTMLInputElement | null>(null)
const galleryUrl = ref('')

/**
 * 表单模型。
 *
 * 数字字段一律用字符串承载（`priceYuan` / `stockText` …）：
 * el-input 绑定 number 时，清空输入框会得到 `null`，再参与计算就成了 NaN，
 * 页面上出现 "NaN" 字样。字符串 + 提交前统一转换更稳。
 */
const form = reactive({
  name: '',
  subTitle: '',
  categoryId: undefined as number | undefined,
  spec: '',
  unit: '枚',
  productType: 1,
  priceYuan: '',
  pointsPriceText: '',
  stockText: '',
  exchangeLimitText: '',
  coverUrl: '',
  images: [] as string[],
  description: '',
  isHot: 0,
  status: 1,
  sortText: ''
})

const categoryOptions = ref<Array<{ id: number; label: string }>>([])

const productId = computed(() => {
  const raw = route.params.id
  if (!raw) return undefined
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : undefined
})

const isEdit = computed(() => productId.value !== undefined)

/** 价格换算提示，边输边显示，避免"我填的是元还是分"的困惑 */
const centsHint = computed(() => {
  const yuan = Number(form.priceYuan)
  if (!Number.isFinite(yuan) || form.priceYuan === '') return '当前为 0 分'
  return `当前为 ${Math.round(yuan * 100)} 分`
})

/** 分类树拍平成下拉选项，用缩进表示层级 */
function flattenCategories(nodes: CategoryVO[], depth = 0): Array<{ id: number; label: string }> {
  const out: Array<{ id: number; label: string }> = []
  for (const node of nodes) {
    out.push({ id: node.id, label: `${'　'.repeat(depth)}${node.name}` })
    if (node.children && node.children.length) {
      out.push(...flattenCategories(node.children, depth + 1))
    }
  }
  return out
}

function toText(v: number | undefined): string {
  return v === undefined || v === null ? '' : String(v)
}

/** 空字符串 → undefined，避免把 0 误当成"未填" */
function toInt(text: string): number | undefined {
  const t = text.trim()
  if (t === '') return undefined
  const n = Number(t)
  return Number.isFinite(n) ? Math.trunc(n) : undefined
}

// ---------------------------------------------------------------------------
// 图片
// ---------------------------------------------------------------------------

function pickCover() {
  coverInput.value?.click()
}

function pickGallery() {
  galleryInput.value?.click()
}

async function doUpload(file: File): Promise<string> {
  uploading.value = true
  try {
    return await uploadFile(file, 'product')
  } finally {
    uploading.value = false
  }
}

async function onCoverPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空，保证同一张图能连续选两次都能触发 change
  if (!file) return
  try {
    form.coverUrl = await doUpload(file)
    ElMessage.success('上传成功')
  } catch (error) {
    console.error('[移动端商品] 封面上传失败', error)
    ElMessage.error('上传失败，请重试')
  }
}

async function onGalleryPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    const url = await doUpload(file)
    form.images.push(url)
  } catch (error) {
    console.error('[移动端商品] 轮播图上传失败', error)
    ElMessage.error('上传失败，请重试')
  }
}

function addGalleryUrl() {
  const url = galleryUrl.value.trim()
  if (!url) return
  form.images.push(url)
  galleryUrl.value = ''
}

// ---------------------------------------------------------------------------
// 分步校验
// ---------------------------------------------------------------------------

/**
 * 每一步的必填校验。
 *
 * 为什么不在最后一次性校验：用户在手机上填到第 4 步才被告知"第 1 步少填了名字"，
 * 还得往回翻三屏 —— 这是典型的移动端反模式。
 * 每步结束就校验，问题当场暴露。
 */
function validateStep(index: number): boolean {
  if (index === 0) {
    if (!form.name.trim()) return fail('请填写商品名称')
    if (!form.categoryId) return fail('请选择所属分类')
    if (!form.unit.trim()) return fail('请填写计量单位')
    if (!form.productType) return fail('请选择商品类型')
  }
  if (index === 1) {
    if (form.priceYuan === '' || !Number.isFinite(Number(form.priceYuan))) {
      return fail('请填写单价')
    }
    if (Number(form.priceYuan) < 0) return fail('单价不能为负数')
    if (form.stockText === '' || !Number.isFinite(Number(form.stockText))) {
      return fail('请填写库存')
    }
    if (Number(form.stockText) < 0) return fail('库存不能为负数')
  }
  return true
}

function fail(message: string): false {
  ElMessage.warning(message)
  return false
}

function next() {
  if (!validateStep(step.value)) return
  step.value = Math.min(step.value + 1, STEPS.length - 1)
}

// ---------------------------------------------------------------------------
// 提交
// ---------------------------------------------------------------------------

function buildPayload(): ProductSaveDTO {
  return {
    id: productId.value,
    categoryId: form.categoryId,
    name: form.name.trim(),
    subTitle: form.subTitle.trim() || undefined,
    spec: form.spec.trim() || undefined,
    coverUrl: form.coverUrl.trim() || undefined,
    images: form.images.length ? form.images : undefined,
    description: form.description.trim() || undefined,
    productType: form.productType,
    // 元 → 分。四舍五入避免 12.3 * 100 = 1229.9999 这种浮点误差
    price: Math.round(Number(form.priceYuan) * 100),
    pointsPrice: toInt(form.pointsPriceText),
    unit: form.unit.trim(),
    stock: toInt(form.stockText),
    exchangeLimit: toInt(form.exchangeLimitText),
    isHot: form.isHot,
    status: form.status,
    sort: toInt(form.sortText)
  }
}

async function save() {
  // 允许在第 4 步直接保存，但前面的必填项仍要复查一遍
  for (let i = 0; i < 2; i++) {
    if (!validateStep(i)) {
      step.value = i
      return
    }
  }

  saving.value = true
  try {
    const payload = buildPayload()
    if (isEdit.value) {
      await updateProduct(payload)
      ElMessage.success('已保存')
    } else {
      await createProduct(payload)
      ElMessage.success('已创建')
    }
    router.back()
  } catch (error) {
    console.error('[移动端商品] 保存失败', error)
    ElMessage.error('保存失败，请检查填写内容')
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------

async function loadCategories() {
  try {
    const tree = await getCategoryTree()
    categoryOptions.value = flattenCategories(tree ?? [])
  } catch (error) {
    console.error('[移动端商品] 分类加载失败', error)
  }
}

async function loadProduct() {
  if (!productId.value) return
  const detail = await getProduct(productId.value)
  form.name = detail.name ?? ''
  form.subTitle = detail.subTitle ?? ''
  form.categoryId = detail.categoryId
  form.spec = detail.spec ?? ''
  form.unit = detail.unit ?? '枚'
  form.productType = detail.productType ?? 1
  form.priceYuan = detail.price === undefined ? '' : (detail.price / 100).toFixed(2)
  form.pointsPriceText = toText(detail.pointsPrice)
  form.stockText = toText(detail.stock)
  form.exchangeLimitText = toText(detail.exchangeLimit)
  form.coverUrl = detail.coverUrl ?? ''
  form.images = detail.images ?? []
  form.description = detail.description ?? ''
  form.isHot = detail.isHot ?? 0
  form.status = detail.status ?? 1
  form.sortText = toText(detail.sort)
}

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([loadCategories(), loadProduct()])
  } catch (error) {
    console.error('[移动端商品] 初始化失败', error)
    ElMessage.error('加载商品失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ---------------- 步骤条 ---------------- */
.steps {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 12px;
  background: #fff;
  border-bottom: 1px solid var(--m-line);
}

.steps__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: var(--m-text-3);
}

.steps__dot {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  background: #f2f3f5;
  color: var(--m-text-3);
}

.steps__label {
  font-size: 11px;
}

.steps__item.active {
  color: var(--m-primary);
}

.steps__item.active .steps__dot {
  color: #fff;
  background: var(--m-primary);
}

.steps__item.done .steps__dot {
  color: #fff;
  background: var(--m-success);
}

/* ---------------- 提示 ---------------- */
.tip {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--m-text-3);
}

/* ---------------- 封面 ---------------- */
.cover {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.cover__img,
.cover__empty {
  width: 84px;
  height: 84px;
  border-radius: 8px;
  object-fit: cover;
  background: #f2f3f5;
}

.cover__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--m-text-3);
}

.cover__ops {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mini {
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid var(--m-primary);
  background: #fff;
  color: var(--m-primary);
  font-size: 13px;
  cursor: pointer;
}

.mini--danger {
  border-color: var(--m-danger);
  color: var(--m-danger);
}

/* ---------------- 轮播图 ---------------- */
.gallery {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.gallery__item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  background: #f2f3f5;
}

.gallery__item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery__del {
  position: absolute;
  top: 0;
  right: 0;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 0 0 0 8px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.gallery__add {
  width: 72px;
  height: 72px;
  border-radius: 8px;
  border: 1px dashed #c8cdd4;
  background: #fafbfc;
  color: var(--m-text-3);
  font-size: 22px;
  cursor: pointer;
}

.gallery__add-url {
  display: flex;
  gap: 8px;
}

/* ---------------- 开关行 ---------------- */
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid var(--m-line);
  font-size: 14px;
  color: var(--m-text);
}

.switch-row:last-of-type {
  border-bottom: none;
}
</style>
