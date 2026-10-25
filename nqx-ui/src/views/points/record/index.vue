<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  CHANGE_TYPE_OPTIONS,
  exportPointsRecord,
  pagePointsRecord,
  type AdminPointsQuery,
  type PointsRecordVO
} from '@/api/points'
import { formatDateTime, stripEmpty } from '@/utils/format'
import { exportAndDownload } from '@/utils/download'

/**
 * 积分流水。
 *
 * 这是<a>只读的账本视图</a>：积分不可能在这里被修改，任何变动都只能通过
 * 「积分账户 → 调整积分」或业务动作（回收结算、每日答题）产生。
 * 因此本页没有工具栏按钮，只有一个检索区 + 表格。
 *
 * 筛选条件与积分账户页保持一致，另外多了「变动类型」与「业务单号」——
 * 排查某笔积分时通常是从 bizNo（订单号 / QUIZ-{userId}-{date} / ADJUST-{uuid}）入手，
 * 所以 bizNo 用模糊匹配而不是精确匹配：管理员往往只记得单号的前半段。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  userId: undefined as number | undefined,
  nickname: '',
  changeType: undefined as number | undefined,
  bizNo: ''
})

/** 变动时间范围单独存：el-date-picker 的区间绑的是数组 */
const changeRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<PointsRecordVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: AdminPointsQuery = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      userId: query.userId,
      nickname: query.nickname || undefined,
      changeType: query.changeType,
      bizNo: query.bizNo || undefined,
      // 后端 AdminPointsQuery 的 startDate / endDate 是 LocalDate（YYYY-MM-DD），
      // 且 SQL 用 `create_time < endDate + 1 天` 表达闭区间，
      // 因此这里传日期即可，"选到 10-15"就包含 10-15 全天。
      startDate: changeRange.value?.[0],
      endDate: changeRange.value?.[1]
    }
    const res = await pagePointsRecord(stripEmpty(params))
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[积分流水] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.userId = undefined
  query.nickname = ''
  query.changeType = undefined
  query.bizNo = ''
  changeRange.value = undefined
  query.pageNum = 1
  fetchList()
}

/* ------------------------------ 导出 ------------------------------ */

/**
 * 导出当前筛选条件下的积分流水。
 *
 * 与列表共用同一套筛选条件（去掉分页），保证"列表看到什么、导出就是什么"。
 * 日期用的是 `startDate`/`endDate`（LocalDate），不是订单页那种 `startTime`/`endTime` ——
 * 三个页面的时间字段名并不统一，抄错会变成"导出全部数据"。
 */
async function handleExport() {
  try {
    const params: AdminPointsQuery = stripEmpty({
      ...query,
      startDate: changeRange.value?.[0],
      endDate: changeRange.value?.[1],
      pageNum: undefined,
      pageSize: undefined
    })
    await exportAndDownload(exportPointsRecord(params), '积分流水')
  } catch (error) {
    // exportAndDownload 已弹出后端提示，这里只记日志避免重复
    console.warn('[积分流水] 导出失败', error)
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

/**
 * 变动值展示。
 *
 * 正数带 `+` 号：光看「50」和「-50」还能分清，但一列对齐的数字里
 * 缺了正号会让人下意识以为都是减少（尤其是财务口径的表格）。
 */
function formatChange(value?: number) {
  if (value === null || value === undefined) return '-'
  return value > 0 ? `+${value}` : `${value}`
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="用户ID">
          <el-input-number
            v-model="query.userId"
            :min="1"
            :controls="false"
            placeholder="精确匹配"
            style="width: 130px"
          />
        </el-form-item>

        <el-form-item label="用户昵称">
          <el-input
            v-model="query.nickname"
            placeholder="支持模糊匹配"
            clearable
            style="width: 150px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="变动类型">
          <el-select v-model="query.changeType" placeholder="全部" clearable style="width: 150px">
            <el-option
              v-for="opt in CHANGE_TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="业务单号">
          <el-input
            v-model="query.bizNo"
            placeholder="支持模糊匹配"
            clearable
            style="width: 170px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="变动时间">
          <el-date-picker
            v-model="changeRange"
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
          <!-- 权限点 points:record:export 早已存在，但页面上一直没有入口 -->
          <el-button
            v-permission="'points:record:export'"
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
        <span class="table-title">积分流水（共 {{ total }} 条）</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="id" label="流水号" width="90" align="center" />
        <el-table-column prop="userId" label="用户ID" width="100" align="center" />

        <el-table-column prop="nickname" label="用户昵称" min-width="130">
          <template #default="{ row }">
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <span v-else class="text-muted">用户已删除</span>
          </template>
        </el-table-column>

        <el-table-column label="变动类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.changeTypeText || '其它' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="变动积分" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.changeValue > 0 ? 'text-income' : 'text-spend'">
              {{ formatChange(row.changeValue) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="变动后余额" width="120" align="right">
          <template #default="{ row }">{{ row.balanceAfter ?? '-' }}</template>
        </el-table-column>

        <el-table-column label="业务单号" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.bizNo">{{ row.bizNo }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>

        <el-table-column label="变动时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无积分流水数据" :image-size="80" />
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

/* 收入用绿色、支出用红色：与用户端"积分增加/减少"的直觉一致 */
.text-income {
  color: #67c23a;
  font-weight: 600;
}

.text-spend {
  color: #f56c6c;
  font-weight: 600;
}
</style>
