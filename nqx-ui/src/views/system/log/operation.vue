<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  getOperationLogDetail,
  OPERATOR_TYPE_OPTIONS,
  pageOperationLogs,
  type SysLogQuery,
  type SysOperationLogDetailVO,
  type SysOperationLogVO
} from '@/api/system'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 操作日志查询。
 *
 * 这一页只读：日志由后端 @Log 注解在切面里异步落库，数据是真实产生的，
 * 因此没有增删改，也没有给"查看日志"这个动作本身再记一条日志
 * （否则翻 10 页就会多出 10 条"查看日志"，把真正有价值的记录冲淡）。
 *
 * 一个关键设计：列表接口刻意不返回 requestParam / responseResult
 * （写入时各截断到 4000 字符，一页 20 条最坏就是 160KB），
 * 完整内容只在点开详情时按需拉取，见 openDetail。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<SysLogQuery>({
  pageNum: 1,
  pageSize: 10,
  module: '',
  userName: '',
  status: undefined
})

/** 操作时间范围单独存：el-date-picker 的区间绑的是数组，不好直接塞进 query */
const timeRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<SysOperationLogVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: SysLogQuery = {
      ...query,
      startDate: timeRange.value?.[0],
      endDate: timeRange.value?.[1]
    }
    const res = await pageOperationLogs(stripEmpty(params))
    // 数据为空时后端返回 records: []，这里再兜一层 || []，
    // 避免任何字段缺失就让整个表格渲染失败（空数据必须优雅显示）
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[操作日志] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.module = ''
  query.userName = ''
  query.status = undefined
  timeRange.value = undefined
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

/* ------------------------------ 展示辅助 ------------------------------ */

/** 操作人类型标签，与后端 CommonConstants.OPERATOR_TYPE_* 一一对应 */
const operatorTypeLabel = (type?: number) =>
  OPERATOR_TYPE_OPTIONS[type === 1 ? 1 : type === 3 ? 3 : 2].label
const operatorTypeTag = (type?: number) =>
  OPERATOR_TYPE_OPTIONS[type === 1 ? 1 : type === 3 ? 3 : 2].tag

/** 耗时超过 1 秒标红：后台接口正常都在百毫秒内，慢请求一眼就能看到 */
const isSlowRequest = (costTime?: number) => (costTime ?? 0) >= 1000

/* ------------------------------ 详情抽屉 ------------------------------ */

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<SysOperationLogDetailVO | null>(null)

async function openDetail(row: SysOperationLogVO) {
  detail.value = null
  drawerVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getOperationLogDetail(row.id)
  } catch (error) {
    console.error('[操作日志] 详情加载失败', error)
    // 详情拉取失败时至少把列表已有字段显示出来，抽屉不至于空白
    detail.value = { ...row }
  } finally {
    detailLoading.value = false
  }
}

/**
 * JSON 美化。
 *
 * 落库的入参是 JSON 字符串，直接贴在页面上是一整行、无法阅读。
 * 但也不能无条件 JSON.parse：日志里存的是"脱敏后的入参序列化结果"，
 * 极端情况下（例如手工写库、或参数本身是原始字符串）可能不是合法 JSON，
 * 那时原样展示即可 —— 宁可排版差一点，也不能因为解析失败让详情页空白。
 */
function prettyJson(text?: string): string {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="业务模块">
          <el-input
            v-model="query.module"
            placeholder="如 系统管理"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="操作人">
          <el-input
            v-model="query.userName"
            placeholder="支持模糊匹配"
            clearable
            style="width: 150px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item label="操作时间">
          <el-date-picker
            v-model="timeRange"
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
        <span class="table-title">操作日志（共 {{ total }} 条）</span>
        <el-button :icon="'Refresh'" @click="fetchList">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column label="操作时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作人" width="160">
          <template #default="{ row }">
            <div class="cell-name">{{ row.userName || '-' }}</div>
            <el-tag :type="operatorTypeTag(row.userType)" size="small" effect="plain">
              {{ operatorTypeLabel(row.userType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="module" label="模块" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.module || '-' }}</template>
        </el-table-column>

        <el-table-column prop="operation" label="操作" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.operation || '-' }}</template>
        </el-table-column>

        <el-table-column label="请求" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" class="method-tag">{{ row.requestMethod }}</el-tag>
            <span class="cell-sub">{{ row.requestUrl }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="ip" label="IP" width="130">
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>

        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-danger': isSlowRequest(row.costTime) }">
              {{ row.costTime ?? '-' }} ms
            </span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMsg" class="text-danger">{{ row.errorMsg }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无操作日志" :image-size="80" />
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

    <!-- 详情抽屉：requestParam / responseResult 只在这里按需加载 -->
    <el-drawer v-model="drawerVisible" title="操作日志详情" size="60%">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="操作人">
            {{ detail.userName || '-' }}
            <el-tag :type="operatorTypeTag(detail.userType)" size="small" effect="plain">
              {{ operatorTypeLabel(detail.userType) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.status === 1 ? 'success' : 'danger'" size="small">
              {{ detail.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="业务模块">{{ detail.module || '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作描述">{{ detail.operation || '-' }}</el-descriptions-item>
          <el-descriptions-item label="请求方式">{{ detail.requestMethod || '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ detail.costTime ?? '-' }} ms</el-descriptions-item>
          <el-descriptions-item label="请求地址" :span="2">
            <span class="mono">{{ detail.requestUrl || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="目标方法" :span="2">
            <span class="mono">{{ detail.method || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="IP">{{ detail.ip || '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">
            {{ formatDateTime(detail.createTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="链路追踪 ID" :span="2">
            <span class="mono">{{ detail.traceId || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMsg" label="异常信息" :span="2">
            <span class="text-danger">{{ detail.errorMsg }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="json-block">
          <div class="json-title">请求参数（已脱敏、最长 4000 字符）</div>
          <pre v-if="detail?.requestParam" class="json-body">{{ prettyJson(detail.requestParam) }}</pre>
          <el-empty v-else description="该操作未记录请求参数" :image-size="60" />
        </div>

        <div class="json-block">
          <div class="json-title">响应结果（仅少数接口开启记录）</div>
          <pre v-if="detail?.responseResult" class="json-body">{{ prettyJson(detail.responseResult) }}</pre>
          <el-empty v-else description="该操作未记录响应结果" :image-size="60" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
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

.method-tag {
  margin-right: 6px;
}

.text-muted {
  color: #c0c4cc;
}

.text-danger {
  color: #f56c6c;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  word-break: break-all;
}

.json-block {
  margin-top: 16px;
}

.json-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.json-body {
  margin: 0;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
