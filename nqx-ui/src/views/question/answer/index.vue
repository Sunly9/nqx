<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { exportUserAnswer, pageUserAnswer, type AdminAnswerQuery, type UserAnswerVO } from '@/api/question'
import { formatDateTime, stripEmpty } from '@/utils/format'
import { exportAndDownload } from '@/utils/download'

/**
 * 答题记录。
 *
 * 这是"每日问答发出去的积分到底该不该发"的核对入口，因此三个信息缺一不可：
 * 谁（昵称）、答的哪道题（题干）、答对了没（判定 + 该题标准答案）。
 * 后端用 LEFT JOIN 把昵称与题干带出来，SQL 见 `UserAnswerMapper.xml`。
 *
 * 数据目前是空的（业务数据已重置），因此本页对空数据必须完全优雅 ——
 * `records` 缺失时退化成空数组，表格显示「暂无数据」而不是报错。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  nickname: '',
  userId: undefined as number | undefined,
  questionId: undefined as number | undefined,
  isCorrect: undefined as number | undefined
})

/** 答题日期范围单独存：区间组件绑的是数组 */
const answerRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<UserAnswerVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: AdminAnswerQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      nickname: query.nickname || undefined,
      userId: query.userId,
      questionId: query.questionId,
      isCorrect: query.isCorrect,
      // question_date 是 DATE，后端按下闭区间处理（选到 10-15 就包含 10-15）
      startDate: answerRange.value?.[0],
      endDate: answerRange.value?.[1]
    }
    const res = await pageUserAnswer(stripEmpty(params))
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[答题记录] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.nickname = ''
  query.userId = undefined
  query.questionId = undefined
  query.isCorrect = undefined
  answerRange.value = undefined
  query.pageNum = 1
  fetchList()
}

/* ------------------------------ 导出 ------------------------------ */

/**
 * 导出当前筛选条件下的答题记录。
 *
 * 与列表共用同一套筛选条件（去掉分页）。日期字段是 `startDate`/`endDate`
 * （question_date 是 DATE 类型，后端按下闭区间处理）。
 *
 * 导出的表里同时有「标准答案」和「用户答案」—— 只看"是否正确"
 * 无法判断是题目配错了还是用户不会，而这两者的处理方式完全不同。
 */
async function handleExport() {
  try {
    const params: AdminAnswerQuery = stripEmpty({
      ...query,
      startDate: answerRange.value?.[0],
      endDate: answerRange.value?.[1],
      pageNum: undefined,
      pageSize: undefined
    })
    await exportAndDownload(exportUserAnswer(params), '答题记录')
  } catch (error) {
    // exportAndDownload 已弹出后端提示，这里只记日志避免重复
    console.warn('[答题记录] 导出失败', error)
  }
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

/** 耗时展示：秒 → "1分30秒"，纯秒数在几十秒以上时不直观 */
function formatCostTime(seconds?: number) {
  if (seconds === null || seconds === undefined) return '-'
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
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

        <el-form-item label="题目ID">
          <el-input-number
            v-model="query.questionId"
            :min="1"
            :controls="false"
            placeholder="精确匹配"
            style="width: 120px"
          />
        </el-form-item>

        <el-form-item label="是否答对">
          <el-select v-model="query.isCorrect" placeholder="全部" clearable style="width: 110px">
            <el-option label="答对" :value="1" />
            <el-option label="答错" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item label="答题日期">
          <el-date-picker
            v-model="answerRange"
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
          <!-- 权限点 question:answer:export 早已存在，但页面上一直没有入口 -->
          <el-button
            v-permission="'question:answer:export'"
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

    <!-- 列表区 -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <span class="table-title">答题记录（共 {{ total }} 条）</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="id" label="记录号" width="90" align="center" />
        <el-table-column prop="userId" label="用户ID" width="100" align="center" />

        <el-table-column prop="nickname" label="用户昵称" width="140">
          <template #default="{ row }">
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <span v-else class="text-muted">用户已删除</span>
          </template>
        </el-table-column>

        <el-table-column label="题目" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.questionContent">{{ row.questionContent }}</span>
            <span v-else class="text-muted">题目已删除（ID: {{ row.questionId }}）</span>
          </template>
        </el-table-column>

        <el-table-column label="用户答案" width="100" align="center">
          <template #default="{ row }">{{ row.userAnswer || '-' }}</template>
        </el-table-column>

        <el-table-column label="标准答案" width="100" align="center">
          <template #default="{ row }">{{ row.correctAnswer || '-' }}</template>
        </el-table-column>

        <el-table-column label="判定" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isCorrect === 1 ? 'success' : 'danger'" size="small">
              {{ row.isCorrect === 1 ? '答对' : '答错' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="获得积分" width="100" align="right">
          <template #default="{ row }">{{ row.gainPoints ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="答题耗时" width="110" align="right">
          <template #default="{ row }">{{ formatCostTime(row.costTime) }}</template>
        </el-table-column>

        <el-table-column label="答题日期" width="120" align="center">
          <template #default="{ row }">{{ row.questionDate || '-' }}</template>
        </el-table-column>

        <el-table-column label="答题时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无答题记录数据" :image-size="80" />
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
  </div>
</template>

<style scoped>
.table-title {
  font-weight: 600;
}

.text-muted {
  color: #c0c4cc;
}
</style>
