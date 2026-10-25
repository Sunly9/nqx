<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  changeQuestionStatus,
  createQuestion,
  deleteQuestion,
  getQuestion,
  optionKeyAt,
  pageQuestion,
  parseOptions,
  updateQuestion,
  type QuestionOption,
  type QuestionQuery,
  type QuestionSaveDTO,
  type QuestionVO
} from '@/api/question'
import { formatDate, stripEmpty } from '@/utils/format'

/**
 * 题库管理。
 *
 * <h3>两个刻意的设计选择</h3>
 *
 * <b>1. 正确答案用"勾选项"而不是让管理员手打字母。</b>
 * 手打 `"A"` / `"ABD"` 时，打错的后果是最难查的一类问题：
 * 题目能存进库、用户能看到题、接口返回 200，只是<b>怎么答都判错</b>，
 * 而用户通常不会来反馈"这题答案错了吧"，只会默默不再答题。
 * 用勾选（复选/单选）后，正确答案天然是选项 key 的子集，
 * 但后端仍保留同样的校验 —— 前端约束只是省掉一次无效请求，不是安全边界。
 *
 * <b>2. 发布/下线/草稿是三态，不是一个开关。</b>
 * `status` 有 0/1/2 三个值，"已下线"的题不会再被随机抽中，
 * 但它与"草稿"的区别是"曾经发布过"，运营靠这个区别判断哪些题是改过又撤下的。
 * 因此行内按钮按当前状态给出<b>可达的目标状态</b>，而不是简单地在 0/1 之间来回切。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  content: '',
  questionType: undefined as number | undefined,
  difficulty: undefined as number | undefined,
  status: undefined as number | undefined
})

/** 发布/生效日期范围，同样因为区间组件绑数组而单独存 */
const publishRange = ref<[string, string] | undefined>()

const questionTypeOptions = [
  { label: '单选题', value: 1 },
  { label: '多选题', value: 2 },
  { label: '判断题', value: 3 }
]

const difficultyOptions = [
  { label: '简单', value: 1 },
  { label: '中等', value: 2 },
  { label: '困难', value: 3 }
]

const statusOptions = [
  { label: '草稿', value: 0 },
  { label: '已发布', value: 1 },
  { label: '已下线', value: 2 }
]

const loading = ref(false)
const tableData = ref<QuestionVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: QuestionQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      content: query.content || undefined,
      questionType: query.questionType,
      difficulty: query.difficulty,
      status: query.status,
      startDate: publishRange.value?.[0],
      endDate: publishRange.value?.[1]
    }
    const res = await pageQuestion(stripEmpty(params))
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[题库管理] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.content = ''
  query.questionType = undefined
  query.difficulty = undefined
  query.status = undefined
  publishRange.value = undefined
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

function typeLabel(type?: number) {
  return questionTypeOptions.find((o) => o.value === type)?.label || '-'
}

function difficultyLabel(difficulty?: number) {
  return difficultyOptions.find((o) => o.value === difficulty)?.label || '-'
}

function statusLabel(status?: number) {
  return statusOptions.find((o) => o.value === status)?.label || '-'
}

/** 状态标签配色：草稿灰、已发布绿、已下线橙（提醒它曾经上线过） */
function statusTagType(status?: number) {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  return 'info'
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */

const dialogVisible = ref(false)
const dialogTitle = ref('新增题目')
const submitting = ref(false)
const loadingDetail = ref(false)
const formRef = ref<FormInstance>()

interface QuestionForm {
  id?: number
  content: string
  questionType: number
  /** 选项列表，可动态增删 */
  options: QuestionOption[]
  /** 正确答案的多选值。单选/判断题只允许一个元素 */
  correctAnswers: string[]
  analysis: string
  points: number
  difficulty: number
  publishDate: string
  sort: number
}

function emptyForm(): QuestionForm {
  return {
    id: undefined,
    content: '',
    questionType: 1,
    // 默认给 4 个空选项：与初始化题库的形态一致，管理员大多只需填内容
    options: [
      { key: 'A', value: '' },
      { key: 'B', value: '' },
      { key: 'C', value: '' },
      { key: 'D', value: '' }
    ],
    correctAnswers: [],
    analysis: '',
    points: 10,
    difficulty: 1,
    publishDate: '',
    sort: 0
  }
}

const form = reactive<QuestionForm>(emptyForm())

const rules: FormRules = {
  content: [
    { required: true, message: '请输入题干', trigger: 'blur' },
    { max: 500, message: '题干不能超过 500 个字符', trigger: 'blur' }
  ],
  points: [
    { required: true, message: '请输入答对得分', trigger: 'blur' },
    {
      // 与后端 QuestionSaveDTO 的 @Min(1) @Max(100) 保持一致，省一次无效请求
      validator: (_rule, value, callback) => {
        if (value === undefined || value === null) callback(new Error('请输入答对得分'))
        else if (value <= 0) callback(new Error('答对得分必须大于 0'))
        else if (value > 100) callback(new Error('答对得分不能超过 100'))
        else callback()
      },
      trigger: 'blur'
    }
  ],
  correctAnswers: [
    {
      validator: (_rule, value, callback) => {
        if (!value || value.length === 0) callback(new Error('请勾选正确答案'))
        else callback()
      },
      trigger: 'change'
    }
  ]
}

/** 是否单选语义（单选/判断都只能有一个答案） */
const singleAnswer = computed(() => form.questionType !== 2)

function resetForm() {
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

function handleAdd() {
  resetForm()
  dialogTitle.value = '新增题目'
  dialogVisible.value = true
}

/**
 * 编辑。
 *
 * 列表接口为了控制响应体不返回 options / analysis，因此必须再取一次详情。
 * 若拿列表行直接回填，选项会是空的 —— 管理员一保存就把这道题的选项清空了，
 * 而且这种"看不出来"的数据损坏最难发现。
 */
async function handleEdit(row: QuestionVO) {
  resetForm()
  dialogTitle.value = `编辑题目：${row.content.slice(0, 20)}${row.content.length > 20 ? '…' : ''}`
  dialogVisible.value = true
  loadingDetail.value = true
  try {
    const detail = await getQuestion(row.id)
    const options = parseOptions(detail.options)
    Object.assign(form, {
      id: detail.id,
      content: detail.content ?? '',
      questionType: detail.questionType ?? 1,
      options: options.length
        ? options
        : [
            { key: 'A', value: '' },
            { key: 'B', value: '' }
          ],
      // 后端返回的正确答案是 "ABD" 这种紧凑形式，拆成数组给多选框用
      correctAnswers: (detail.correctAnswer || '').split('').filter((c) => c.trim() !== ''),
      analysis: detail.analysis ?? '',
      points: detail.points ?? 10,
      difficulty: detail.difficulty ?? 1,
      publishDate: detail.publishDate ?? '',
      sort: detail.sort ?? 0
    })
    formRef.value?.clearValidate()
  } catch (error) {
    console.error('[题库管理] 加载题目详情失败', error)
    dialogVisible.value = false
  } finally {
    loadingDetail.value = false
  }
}

/* ------------------------------ 选项增删 ------------------------------ */

function addOption() {
  if (form.options.length >= 26) {
    ElMessage.warning('最多 26 个选项')
    return
  }
  // key 由位置推导（第 0 个是 A），因此不允许管理员自填 key ——
  // 自填会造出 "A/B/B" 或 "X" 这类 key，正确答案勾选框与后端校验都无从下手
  form.options.push({ key: optionKeyAt(form.options.length), value: '' })
}

function removeOption(index: number) {
  if (form.options.length <= 2) {
    ElMessage.warning('至少需要保留 2 个选项')
    return
  }
  form.options.splice(index, 1)
  // 删除后按位置重排 key，保证 A/B/C 连续无空洞
  form.options.forEach((opt, i) => {
    opt.key = optionKeyAt(i)
  })
  // 被删掉的选项如果原本是正确答案，必须同步摘掉，
  // 否则会提交一个"答案不在选项里"的请求，被后端拒绝
  const keys = new Set(form.options.map((o) => o.key))
  form.correctAnswers = form.correctAnswers.filter((k) => keys.has(k))
}

function moveOption(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= form.options.length) return
  const list = form.options
  ;[list[index], list[target]] = [list[target], list[index]]
  // key 是"位置"的函数，交换后必须重排；已勾选的正确答案要先记下它<b>原本指向哪个选项</b>，
  // 重排完再映射回去，否则会把答案平移到别的选项上 ——
  // 那等于悄悄改掉了这道题的答案，是最危险的一种"顺手"实现。
  const checkedValues = form.correctAnswers.map(
    (key) => list.find((o) => o.key === key)?.value ?? ''
  )
  list.forEach((opt, i) => {
    opt.key = optionKeyAt(i)
  })
  form.correctAnswers = list
    .filter((opt) => checkedValues.includes(opt.value))
    .map((opt) => opt.key)
}

/**
 * 切换题型。
 *
 * 判断题固定为「正确 / 错误」两个选项，其它题型清空让管理员重填 ——
 * 把单选题的 4 个选项直接留给判断题，会造出一道"正确、错误、危害土壤、浪费资源"
 * 的四选项判断题，看起来滑稽但确实会被这样提交上来。
 */
function handleTypeChange(type: number) {
  if (type === 3) {
    form.options = [
      { key: 'A', value: '正确' },
      { key: 'B', value: '错误' }
    ]
  } else if (form.options.length === 2 && form.options[0].value === '正确') {
    form.options = [
      { key: 'A', value: '' },
      { key: 'B', value: '' },
      { key: 'C', value: '' },
      { key: 'D', value: '' }
    ]
  }
  if (type !== 2) {
    // 切成单选/判断题时把多选答案收成第一个，避免带着 "ABD" 去提交
    form.correctAnswers = form.correctAnswers.slice(0, 1)
  }
  formRef.value?.clearValidate('correctAnswers')
}

/**
 * 勾选正确答案。
 *
 * 单选/判断题在这里把已选项收成一个：element-plus 的单选框天然只给一个值，
 * 但多选题切回单选时残留的数组必须显式收敛。
 */
function handleAnswerChange() {
  if (singleAnswer.value && form.correctAnswers.length > 1) {
    form.correctAnswers = form.correctAnswers.slice(-1)
  }
}

/* ------------------------------ 提交 ------------------------------ */

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // 选项内容为空的直接拦下：它能通过 JSON 校验，却是道无法作答的题
  const empty = form.options.find((o) => !o.value.trim())
  if (empty) {
    ElMessage.warning(`选项 ${empty.key} 的内容不能为空`)
    return
  }
  if (form.correctAnswers.length === 0) {
    ElMessage.warning('请勾选正确答案')
    return
  }

  const payload: QuestionSaveDTO = {
    id: form.id,
    content: form.content,
    questionType: form.questionType,
    // 后端要的是 JSON 字符串；排序在前端已按位置维护好，这里原样序列化
    options: JSON.stringify(form.options.map((o) => ({ key: o.key, value: o.value.trim() }))),
    // 正确答案提交前排序，保证 "BA" 与 "AB" 落库形态一致
    correctAnswer: [...form.correctAnswers].sort().join(''),
    analysis: form.analysis || undefined,
    points: form.points,
    difficulty: form.difficulty,
    publishDate: form.publishDate || undefined,
    sort: form.sort
  }

  submitting.value = true
  try {
    if (form.id) {
      await updateQuestion(payload)
      ElMessage.success('修改成功')
    } else {
      await createQuestion(payload)
      ElMessage.success('新增成功，当前为草稿状态，请按需发布')
    }
    dialogVisible.value = false
    fetchList()
  } catch (error) {
    console.error('[题库管理] 保存失败', error)
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 行操作 ------------------------------ */

async function handleChangeStatus(row: QuestionVO, status: number) {
  const actionText = statusLabel(status)
  try {
    await ElMessageBox.confirm(`确定要把该题目改为「${actionText}」吗？`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await changeQuestionStatus(row.id, status)
    ElMessage.success(`已${actionText}`)
    fetchList()
  } catch (error) {
    console.error('[题库管理] 状态变更失败', error)
  }
}

async function handleDelete(row: QuestionVO) {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复，确定要删除题目「${row.content.slice(0, 20)}…」吗？` +
        '已有用户作答的题目不允许删除，请改为下线。',
      '危险操作',
      { type: 'error', confirmButtonText: '确定删除' }
    )
  } catch {
    return
  }
  try {
    await deleteQuestion(row.id)
    ElMessage.success('删除成功')
    // 删掉当前页最后一条时往前翻一页，避免停在空白页
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    fetchList()
  } catch (error) {
    console.error('[题库管理] 删除失败', error)
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="题干关键词">
          <el-input
            v-model="query.content"
            placeholder="支持模糊匹配"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="题型">
          <el-select v-model="query.questionType" placeholder="全部" clearable style="width: 120px">
            <el-option
              v-for="opt in questionTypeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="难度">
          <el-select v-model="query.difficulty" placeholder="全部" clearable style="width: 110px">
            <el-option
              v-for="opt in difficultyOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="发布日期">
          <el-date-picker
            v-model="publishRange"
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
        <span class="table-title">题库列表（共 {{ total }} 条）</span>
        <el-button v-permission="'question:add'" type="primary" :icon="'Plus'" @click="handleAdd">
          新增题目
        </el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="id" label="ID" width="70" align="center" />

        <el-table-column prop="content" label="题干" min-width="260" show-overflow-tooltip />

        <el-table-column label="题型" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ typeLabel(row.questionType) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="正确答案" width="100" align="center">
          <template #default="{ row }">{{ row.correctAnswer || '-' }}</template>
        </el-table-column>

        <el-table-column label="答对得分" width="90" align="right">
          <template #default="{ row }">{{ row.points ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="难度" width="80" align="center">
          <template #default="{ row }">{{ difficultyLabel(row.difficulty) }}</template>
        </el-table-column>

        <el-table-column label="发布/生效日期" width="130" align="center">
          <template #default="{ row }">{{ formatDate(row.publishDate) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="sort" label="排序" width="70" align="right" />

        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-permission="'question:edit'" link type="primary" @click="handleEdit(row)">
                编辑
              </el-button>
              <el-button
                v-if="row.status !== 1"
                v-permission="'question:changeStatus'"
                link
                type="success"
                @click="handleChangeStatus(row, 1)"
              >
                发布
              </el-button>
              <el-button
                v-else
                v-permission="'question:changeStatus'"
                link
                type="warning"
                @click="handleChangeStatus(row, 2)"
              >
                下线
              </el-button>
              <el-button
                v-permission="'question:delete'"
                link
                type="danger"
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无题目数据" :image-size="80" />
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
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" destroy-on-close>
      <el-form
        ref="formRef"
        v-loading="loadingDetail"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="题干" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="请输入题干，如：纽扣电池应该投入哪一类垃圾桶？"
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="题型" prop="questionType">
              <el-select
                v-model="form.questionType"
                style="width: 100%"
                @change="handleTypeChange"
              >
                <el-option
                  v-for="opt in questionTypeOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="难度" prop="difficulty">
              <el-select v-model="form.difficulty" style="width: 100%">
                <el-option
                  v-for="opt in difficultyOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="答对得分" prop="points">
              <el-input-number v-model="form.points" :min="1" :max="100" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 选项：可动态增删 -->
        <el-form-item label="选项" required>
          <div class="option-list">
            <div v-for="(opt, index) in form.options" :key="index" class="option-row">
              <el-tag class="option-key" type="info" effect="dark">{{ opt.key }}</el-tag>
              <el-input
                v-model="opt.value"
                :placeholder="`选项 ${opt.key} 的内容`"
                maxlength="200"
              />
              <el-button
                link
                :disabled="index === 0"
                :icon="'Top'"
                @click="moveOption(index, -1)"
              />
              <el-button
                link
                :disabled="index === form.options.length - 1"
                :icon="'Bottom'"
                @click="moveOption(index, 1)"
              />
              <el-button link type="danger" :icon="'Delete'" @click="removeOption(index)" />
            </div>
            <div class="option-hint">
              选项 key 按顺序自动生成（A/B/C/D…），删除后会自动重排，无需手工维护。
            </div>
            <el-button link type="primary" :icon="'Plus'" @click="addOption">添加选项</el-button>
          </div>
        </el-form-item>

        <el-form-item label="正确答案" prop="correctAnswers">
          <!--
            判断/单选用 radio、多选用 checkbox。
            单选语义下用 checkbox 也能"选一个"，但用户在 UI 上看不出只能选一个，
            误点第二个时的反馈（被静默收成一个）反而更困惑。
          -->
          <el-radio-group
            v-if="singleAnswer"
            v-model="form.correctAnswers"
            @change="handleAnswerChange"
          >
            <el-radio v-for="opt in form.options" :key="opt.key" :value="[opt.key]">
              {{ opt.key }}
            </el-radio>
          </el-radio-group>
          <el-checkbox-group v-else v-model="form.correctAnswers" @change="handleAnswerChange">
            <el-checkbox v-for="opt in form.options" :key="opt.key" :value="opt.key">
              {{ opt.key }}
            </el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">
            勾选后提交的答案是这些 key 的组合（如 ABD）。<b>已有用户作答的题目不允许修改正确答案</b>，
            否则当时被判错的用户会觉得判定不公；如需作废请改为「下线」。
          </div>
        </el-form-item>

        <el-form-item label="答案解析" prop="analysis">
          <el-input
            v-model="form.analysis"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="选填。会在小程序端答题结束后展示，建议写清判定依据"
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="发布/生效日期" prop="publishDate">
              <el-date-picker
                v-model="form.publishDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="默认当天"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="排序" prop="sort">
              <el-input-number v-model="form.sort" :min="0" style="width: 100%" />
              <div class="form-tip">值小的排前面</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-alert
          v-if="!form.id"
          type="info"
          :closable="false"
          show-icon
          title="新增后为草稿状态"
          description="题目先以草稿保存，确认内容无误后，在列表里点「发布」才会推送给用户。"
        />
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.table-title {
  font-weight: 600;
}

.option-list {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.option-key {
  flex: 0 0 auto;
  width: 28px;
  text-align: center;
}

.option-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-bottom: 4px;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
