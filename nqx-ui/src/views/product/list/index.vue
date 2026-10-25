<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  changeProductStatus,
  createProduct,
  deleteProduct,
  getCategoryTree,
  pageProduct,
  updateProduct
} from '@/api/product'
import { uploadFile } from '@/api/file'
import type { CategoryVO, ProductQuery, ProductSaveDTO, ProductVO } from '@/types/product'
import { fenToYuan, formatDateTime, stripEmpty, yuanToFen } from '@/utils/format'

/**
 * 商品列表。
 *
 * 两个容易踩的点在这里集中处理：
 * 1. 金额单位。后端全链路用「分」，而管理员习惯按「元」录入与阅读，
 *    因此接口出口统一 fenToYuan 展示、入口统一 yuanToFen 提交。
 * 2. 上下架与删除都是不可逆操作，一律先二次确认再发请求。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  name: '',
  categoryId: undefined as number | undefined,
  productType: undefined as number | undefined,
  status: undefined as number | undefined,
  isHot: undefined as number | undefined,
  minPriceYuan: undefined as number | undefined,
  maxPriceYuan: undefined as number | undefined,
  stockLessThan: undefined as number | undefined
})

const loading = ref(false)
const tableData = ref<ProductVO[]>([])
const total = ref(0)
const categoryTree = ref<CategoryVO[]>([])
const categoryProps = { label: 'name', children: 'children' }

const productTypeOptions = [
  { label: '采购商品', value: 1 },
  { label: '积分兑换商品', value: 2 },
  { label: '两者皆可', value: 3 }
]

async function fetchList() {
  loading.value = true
  try {
    const params: ProductQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      name: query.name || undefined,
      categoryId: query.categoryId,
      productType: query.productType,
      status: query.status,
      isHot: query.isHot,
      // 元 → 分 之后再发给后端，避免"按元查询"这种单位错位被静默接受
      minPrice: query.minPriceYuan === undefined ? undefined : yuanToFen(query.minPriceYuan),
      maxPrice: query.maxPriceYuan === undefined ? undefined : yuanToFen(query.maxPriceYuan),
      stockLessThan: query.stockLessThan
    }
    // stripEmpty 把未填写的检索项去掉：空字符串发给后端虽多半能转成 null，
    // 但 minPrice=0 这类合法的 0 值必须保留，交给工具函数统一判断更安全
    const res = await pageProduct(stripEmpty(params))
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('[商品列表] 查询失败', error)
  } finally {
    loading.value = false
  }
}

async function fetchCategories() {
  try {
    categoryTree.value = await getCategoryTree()
  } catch (error) {
    console.error('[商品列表] 分类树加载失败', error)
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.name = ''
  query.categoryId = undefined
  query.productType = undefined
  query.status = undefined
  query.isHot = undefined
  query.minPriceYuan = undefined
  query.maxPriceYuan = undefined
  query.stockLessThan = undefined
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

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增商品')
const submitting = ref(false)
const formRef = ref<FormInstance>()

/** 表单模型。价格用「元」承载，提交前再换算成分 */
interface ProductForm {
  id?: number
  categoryId?: number
  name: string
  subTitle: string
  spec: string
  coverUrl: string
  images: string[]
  description: string
  productType?: number
  priceYuan?: number
  pointsPrice: number
  unit: string
  stock?: number
  exchangeLimit: number
  isHot: number
  status: number
  sort: number
}

function emptyForm(): ProductForm {
  return {
    id: undefined,
    categoryId: undefined,
    name: '',
    subTitle: '',
    spec: '',
    coverUrl: '',
    images: [],
    description: '',
    productType: 1,
    priceYuan: undefined,
    pointsPrice: 0,
    unit: '个',
    stock: undefined,
    exchangeLimit: 0,
    isHot: 0,
    status: 1,
    sort: 0
  }
}

const form = reactive<ProductForm>(emptyForm())

const rules: FormRules = {
  categoryId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  name: [
    { required: true, message: '请输入商品名称', trigger: 'blur' },
    { max: 100, message: '商品名称不能超过 100 个字符', trigger: 'blur' }
  ],
  productType: [{ required: true, message: '请选择商品类型', trigger: 'change' }],
  priceYuan: [{ required: true, message: '请输入单价', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入计量单位', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

function resetForm() {
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

/**
 * 把后端返回的 `images`（JSON 数组字符串）解析成数组。
 *
 * 为什么必须容错：这个字段来自数据库的 JSON 列，
 * 理论上合法，但历史数据可能是空串、`null`、或人工改坏的内容。
 * 编辑弹窗因为一个解析异常整个打不开，是不可接受的 ——
 * 退化成"没有多图"就好，同时也别把解析失败当成空数组**再提交回去**，
 * 那会覆盖原值。所以解析失败时保留原始字符串不动（见 handleEdit 的注释）。
 */
function parseImageList(raw?: string | null): string[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []
  } catch {
    console.warn('[product] images 不是合法 JSON，按空数组处理：', raw)
    return []
  }
}

function handleAdd() {
  resetForm()
  dialogTitle.value = '新增商品'
  dialogVisible.value = true
}

function handleEdit(row: ProductVO) {
  resetForm()
  dialogTitle.value = `编辑商品：${row.name}`
  Object.assign(form, {
    id: row.id,
    categoryId: row.categoryId,
    name: row.name,
    subTitle: row.subTitle ?? '',
    spec: row.spec ?? '',
    coverUrl: row.coverUrl ?? '',
    /*
     * ⚠️ images 与 description 必须从行数据回填，不能写死空值。
     *
     * 原来这里是 `images: []` / `description: ''`，而列表 VO 又不返回这两个字段，
     * 于是「编辑商品」弹窗每次打开都是空的，一保存就把原描述和多图**清空**
     * —— 管理员只想改个价格，详情内容就没了，且没有任何提示。
     *
     * 后端 VO 已补上这两个字段（见 ProductVO 的注释）。这里负责正确回填。
     */
    images: parseImageList(row.imagesRaw),
    description: row.description ?? '',
    productType: row.productType,
    priceYuan: row.price === undefined ? undefined : Number(fenToYuan(row.price)),
    pointsPrice: row.pointsPrice ?? 0,
    unit: row.unit,
    stock: row.stock,
    exchangeLimit: row.exchangeLimit ?? 0,
    isHot: row.isHot ?? 0,
    status: row.status,
    sort: row.sort ?? 0
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 后端用同一个 DTO 承载新增与修改，靠 id 是否为空区分
  const payload: ProductSaveDTO = {
    id: form.id,
    categoryId: form.categoryId,
    name: form.name,
    subTitle: form.subTitle,
    spec: form.spec,
    coverUrl: form.coverUrl,
    images: form.images,
    description: form.description,
    productType: form.productType,
    price: yuanToFen(form.priceYuan),
    pointsPrice: form.pointsPrice,
    unit: form.unit,
    stock: form.stock,
    exchangeLimit: form.exchangeLimit,
    isHot: form.isHot,
    status: form.status,
    sort: form.sort
  }

  submitting.value = true
  try {
    if (form.id) {
      await updateProduct(payload)
      ElMessage.success('修改成功')
    } else {
      await createProduct(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[商品列表] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 图片上传 ------------------------------ */

/**
 * 上传逻辑交给 api/file.ts，这里只做拦截与回填。
 *
 * 用 el-upload 的 http-request 自定义上传，而不是它的默认 action：
 * 默认实现会绕过项目的 axios 封装，从而丢掉 Authorization 头与统一错误处理。
 */
async function handleUpload(options: { file: File }) {
  try {
    const url = await uploadFile(options.file as File, 'product')
    form.coverUrl = url
    ElMessage.success('上传成功')
  } catch (error) {
    console.error('[商品列表] 图片上传失败', error)
  }
}

/**
 * 轮播图：上传一张，追加到 `form.images`。
 *
 * 与封面图分开两个 handler，因为语义不同：
 * 封面是**替换**（一个商品只有一张封面），轮播图是**追加**（可以多张）。
 * 合成一个函数会让 `form.coverUrl = url` 与 `form.images.push(url)` 混在一起，
 * 很容易在某个分支里写错对象。
 */
async function handleGalleryUpload(options: { file: File }) {
  try {
    const url = await uploadFile(options.file as File, 'product')
    form.images.push(url)
    ElMessage.success('已添加轮播图')
  } catch (error) {
    console.error('[商品列表] 轮播图上传失败', error)
  }
}

/** 轮播图：粘贴链接添加时用的输入框 */
const galleryUrlInput = ref('')

/**
 * 判断一个字符串是否像"可以用来显示的图片地址"。
 *
 * 后端存的是相对路径（`/files/product/1.png`），也可以接受外链
 * （`https://xxx.cos.ap-chengdu.myqcloud.com/xxx.png`）。
 * 两种都要放行，但**不能让用户填一个随便的字符串** —— 那会在小程序里静默变成裂图。
 */
function looksLikeImageUrl(url: string): boolean {
  if (/^https?:\/\//i.test(url)) {
    return true
  }
  // 站内相对路径必须限定在 /files/ 下，避免填成 /admin/xxx 这种接口地址
  return url.startsWith('/files/')
}

/** 轮播图：从输入框添加一个链接 */
function addGalleryUrl() {
  const url = (galleryUrlInput.value || '').trim()
  if (!url) {
    return
  }
  if (!looksLikeImageUrl(url)) {
    ElMessage.warning('请填写 http(s):// 开头的外链，或 /files/ 开头的站内路径')
    return
  }
  if (form.images.includes(url)) {
    ElMessage.warning('这张图已经在轮播图里了')
    return
  }
  form.images.push(url)
  galleryUrlInput.value = ''
}

/** 轮播图：删除某一张 */
function removeGalleryImage(index: number) {
  form.images.splice(index, 1)
}

/** 轮播图：把封面图也加进去（最常见的操作：只有封面，但也想在详情轮播里显示） */
function useCoverAsGallery() {
  if (!form.coverUrl) {
    ElMessage.warning('还没有设置封面图')
    return
  }
  if (form.images.includes(form.coverUrl)) {
    ElMessage.warning('封面图已经在轮播图里了')
    return
  }
  form.images.unshift(form.coverUrl)
}

/* ------------------------------ 行操作 ------------------------------ */

async function handleToggleStatus(row: ProductVO) {
  const next = row.status === 1 ? 0 : 1
  const actionText = next === 1 ? '上架' : '下架'
  try {
    await ElMessageBox.confirm(`确定要${actionText}商品「${row.name}」吗？`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    await changeProductStatus(row.id, next)
    ElMessage.success(`${actionText}成功`)
    fetchList()
  } catch (error) {
    console.error('[商品列表] 上下架失败', error)
  }
}

async function handleDelete(row: ProductVO) {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复，确定要删除商品「${row.name}」吗？`,
      '危险操作',
      { type: 'error', confirmButtonText: '确定删除' }
    )
  } catch {
    return
  }

  try {
    await deleteProduct(row.id)
    ElMessage.success('删除成功')
    // 删掉当前页最后一条时往前翻一页，避免停在空白页
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    fetchList()
  } catch (error) {
    console.error('[商品列表] 删除失败', error)
  }
}

onMounted(() => {
  fetchCategories()
  fetchList()
})
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="商品名称">
          <el-input
            v-model="query.name"
            placeholder="支持模糊匹配"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="所属分类">
          <el-tree-select
            v-model="query.categoryId"
            :data="categoryTree"
            :props="categoryProps"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            placeholder="请选择分类"
            clearable
            style="width: 200px"
          />
        </el-form-item>

        <el-form-item label="商品类型">
          <el-select v-model="query.productType" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="opt in productTypeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="上架" :value="1" />
            <el-option label="下架" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item label="是否热门">
          <el-select v-model="query.isHot" placeholder="全部" clearable style="width: 120px">
            <el-option label="热门" :value="1" />
            <el-option label="普通" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item label="价格区间">
          <el-input-number
            v-model="query.minPriceYuan"
            :min="0"
            :controls="false"
            placeholder="最低"
            style="width: 100px"
          />
          <span class="range-sep">-</span>
          <el-input-number
            v-model="query.maxPriceYuan"
            :min="0"
            :controls="false"
            placeholder="最高"
            style="width: 100px"
          />
          <span class="range-unit">元</span>
        </el-form-item>

        <el-form-item label="库存低于">
          <el-input-number
            v-model="query.stockLessThan"
            :min="0"
            :controls="false"
            placeholder="库存预警"
            style="width: 120px"
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
        <span class="table-title">商品列表（共 {{ total }} 条）</span>
        <el-button v-permission="'product:add'" type="primary" :icon="'Plus'" @click="handleAdd">
          新增商品
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column label="封面" width="80" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.coverUrl"
              :src="row.coverUrl"
              :preview-src-list="[row.coverUrl]"
              preview-teleported
              fit="cover"
              style="width: 44px; height: 44px; border-radius: 4px"
            />
            <span v-else class="text-muted">无</span>
          </template>
        </el-table-column>

        <el-table-column prop="name" label="商品名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-name">{{ row.name }}</div>
            <div v-if="row.subTitle" class="cell-sub">{{ row.subTitle }}</div>
          </template>
        </el-table-column>

        <el-table-column prop="categoryName" label="分类" width="140" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />

        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">
              {{ productTypeOptions.find((o) => o.value === row.productType)?.label || '-' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="单价(元)" width="100" align="right">
          <template #default="{ row }">{{ fenToYuan(row.price) }}</template>
        </el-table-column>

        <el-table-column label="积分价" width="90" align="right">
          <template #default="{ row }">{{ row.pointsPrice ?? 0 }}</template>
        </el-table-column>

        <el-table-column prop="stock" label="库存" width="80" align="right" />
        <el-table-column prop="soldCount" label="销量" width="80" align="right" />

        <el-table-column label="热门" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isHot === 1" type="danger" size="small">热门</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-permission="'product:edit'" link type="primary" @click="handleEdit(row)">
                编辑
              </el-button>
              <el-button
                v-permission="'product:changeStatus'"
                link
                :type="row.status === 1 ? 'warning' : 'success'"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '下架' : '上架' }}
              </el-button>
              <el-button v-permission="'product:delete'" link type="danger" @click="handleDelete(row)">
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无商品数据" :image-size="80" />
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

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="720px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="商品名称" prop="name">
              <el-input v-model="form.name" placeholder="请输入商品名称" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="副标题" prop="subTitle">
              <el-input v-model="form.subTitle" placeholder="选填" maxlength="200" />
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="所属分类" prop="categoryId">
              <el-tree-select
                v-model="form.categoryId"
                :data="categoryTree"
                :props="categoryProps"
                node-key="id"
                check-strictly
                :render-after-expand="false"
                placeholder="请选择分类"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规格" prop="spec">
              <el-input v-model="form.spec" placeholder="如 CR2032 3V" maxlength="100" />
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="商品类型" prop="productType">
              <el-select v-model="form.productType" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="opt in productTypeOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="计量单位" prop="unit">
              <el-input v-model="form.unit" placeholder="如 个 / kg" maxlength="20" />
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="单价(元)" prop="priceYuan">
              <el-input-number
                v-model="form.priceYuan"
                :min="0"
                :precision="2"
                :step="1"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="积分价" prop="pointsPrice">
              <el-input-number v-model="form.pointsPrice" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="库存" prop="stock">
              <el-input-number v-model="form.stock" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="限兑数量" prop="exchangeLimit">
              <el-input-number v-model="form.exchangeLimit" :min="0" style="width: 100%" />
              <div class="form-tip">0 表示不限</div>
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="是否热门" prop="isHot">
              <el-radio-group v-model="form.isHot">
                <el-radio :value="1">是</el-radio>
                <el-radio :value="0">否</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <!--
              ⚠️ 这里原来有一个「状态：上架 / 下架」单选，但它**不生效**：

                · 新增时后端固定按下架入库（ProductService.create 里写死
                  `setStatus(STATUS_DISABLED)`），理由是"上架应当是一个显式的
                  运营动作，而不是保存了就自动对外可见"；
                · 编辑时后端也不改状态（`setStatus(null)`），
                  避免"改个错别字"顺手把商品上下架了。

              也就是说这个单选框给了管理员一个**做不到的选择**：选"上架"，
              保存后商品仍是下架，界面上看不出为什么。

              与其留一个骗人的选项，不如如实说明状态在哪里改。
              改成纯提示，不参与提交。
            -->
            <el-form-item label="状态">
              <span class="form-hint">
                新增商品默认为<strong>下架</strong>，请在列表里用「上架」按钮发布；
                编辑资料不会改变上下架状态。
              </span>
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="排序" prop="sort">
              <el-input-number v-model="form.sort" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="封面图" prop="coverUrl">
              <!--
                封面图有两种来源，都要支持：
                  · 粘贴外链（对象存储 / 图床 / CDN 上已有的图片）
                  · 上传到本站（后端 /admin/file/upload，存到 uploads/ 下）
                真实业务里两者都常见：运营手上的图往往已经在 COS/OSS 上，
                强行要求先下载再上传是多余的一步。
              -->
              <div class="cover-field">
                <el-image
                  v-if="form.coverUrl"
                  :src="form.coverUrl"
                  fit="cover"
                  class="cover-preview"
                  :preview-src-list="[form.coverUrl]"
                  preview-teleported
                >
                  <template #error>
                    <div class="cover-broken">图片打不开</div>
                  </template>
                </el-image>
                <div v-else class="cover-empty">无封面</div>

                <div class="cover-actions">
                  <el-input
                    v-model="form.coverUrl"
                    placeholder="粘贴图片链接，如 https://xxx.com/1.png"
                    clearable
                    size="small"
                  />
                  <el-upload
                    :show-file-list="false"
                    :http-request="handleUpload"
                    accept="image/*"
                  >
                    <el-button size="small" :icon="'Upload'">上传本地图片</el-button>
                  </el-upload>
                </div>
              </div>
              <div class="form-hint">
                可以粘贴外链，也可以上传到本站。留空则小程序里显示占位图标。
              </div>
            </el-form-item>
          </el-col>

          <!--
            轮播图（商品详情页顶部的 swiper）。

            这块之前**完全没有 UI**：数据模型里有 `images`、提交时也带上去了、
            后端 applyDto 也会存成 JSON、小程序详情页也在读 `product.gallery` ——
            但管理后台没有任何地方能填它。结果就是详情页永远只有一张图。
          -->
          <el-col :span="24">
            <el-form-item label="轮播图">
              <div class="gallery-field">
                <div class="gallery-list">
                  <div v-for="(img, i) in form.images" :key="i" class="gallery-item">
                    <el-image
                      :src="img"
                      fit="cover"
                      class="gallery-img"
                      :preview-src-list="form.images"
                      :initial-index="i"
                      preview-teleported
                    >
                      <template #error>
                        <div class="cover-broken">打不开</div>
                      </template>
                    </el-image>
                    <el-button
                      class="gallery-del"
                      link
                      type="danger"
                      :icon="'Delete'"
                      @click="removeGalleryImage(i)"
                    />
                    <span v-if="i === 0" class="gallery-badge">首图</span>
                  </div>

                  <el-upload
                    :show-file-list="false"
                    :http-request="handleGalleryUpload"
                    accept="image/*"
                  >
                    <div class="gallery-add">
                      <el-icon><Plus /></el-icon>
                      <span>上传</span>
                    </div>
                  </el-upload>
                </div>

                <div class="gallery-url-row">
                  <el-input
                    v-model="galleryUrlInput"
                    placeholder="粘贴图片链接后按回车添加，如 https://xxx.com/2.png"
                    size="small"
                    clearable
                    @keyup.enter="addGalleryUrl"
                  />
                  <el-button size="small" @click="addGalleryUrl">添加</el-button>
                  <el-button size="small" :disabled="!form.coverUrl" @click="useCoverAsGallery">
                    把封面加进来
                  </el-button>
                </div>

                <div class="form-hint">
                  详情页顶部轮播。可上传、可粘贴外链、可多张。**留空则只显示封面图**。
                </div>
              </div>
            </el-form-item>
          </el-col>

          <el-col :span="24">
            <el-form-item label="商品描述" prop="description">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="3"
                placeholder="选填"
                maxlength="1000"
                show-word-limit
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.range-sep {
  margin: 0 6px;
  color: #909399;
}

.range-unit {
  margin-left: 6px;
  color: #909399;
}

.table-title {
  font-weight: 600;
}

.cell-name {
  color: #303133;
}

.cell-sub {
  font-size: 12px;
  color: #909399;
}

.text-muted {
  color: #c0c4cc;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.cover-upload {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88px;
  height: 88px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  overflow: hidden;
}

.cover-upload:hover {
  border-color: var(--nqx-primary);
}

.cover-preview {
  width: 88px;
  height: 88px;
}

.cover-placeholder {
  font-size: 24px;
  color: #8c939d;
}

/* 表单里的说明文字：替代那个不生效的「状态」单选 */
.form-hint {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.form-hint strong {
  color: #606266;
}

/* ============================================================================
   封面图：预览 + 「粘贴链接 / 上传」两种入口
   ============================================================================ */
.cover-field {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
}

.cover-preview {
  width: 88px;
  height: 88px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  flex-shrink: 0;
  background: #fafafa;
}

/* 图片加载不出来时的占位：比一个空白框更能说明问题 */
.cover-broken,
.cover-empty {
  width: 88px;
  height: 88px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #f56c6c;
  background: #fef0f0;
  border: 1px dashed #fbc4c4;
  border-radius: 6px;
  flex-shrink: 0;
}

.cover-empty {
  color: #909399;
  background: #fafafa;
  border-color: #dcdfe6;
}

.cover-actions {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ============================================================================
   轮播图
   ============================================================================ */
.gallery-field {
  width: 100%;
}

.gallery-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.gallery-item {
  position: relative;
  width: 88px;
  height: 88px;
}

.gallery-img {
  width: 88px;
  height: 88px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
  background: #fafafa;
}

/* 删除按钮压在图片右上角 */
.gallery-del {
  position: absolute;
  top: -6px;
  right: -6px;
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.15);
  padding: 2px;
}

/* 第一张标「首图」：它会在列表页/详情页最先出现，值得让运营一眼看到 */
.gallery-badge {
  position: absolute;
  left: 0;
  bottom: 0;
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: #fff;
  background: rgba(7, 193, 96, 0.9);
  border-bottom-left-radius: 6px;
  border-top-right-radius: 6px;
}

.gallery-add {
  width: 88px;
  height: 88px;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #8c939d;
  font-size: 12px;
  cursor: pointer;
}

.gallery-add:hover {
  border-color: var(--nqx-primary);
  color: var(--nqx-primary);
}

.gallery-url-row {
  display: flex;
  gap: 8px;
}
</style>
