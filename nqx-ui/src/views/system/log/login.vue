<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  OPERATOR_TYPE_OPTIONS,
  pageLoginLogs,
  type SysLoginLogVO,
  type SysLogQuery
} from '@/api/system'
import { formatDateTime, stripEmpty } from '@/utils/format'

/**
 * 登录日志查询。
 *
 * 与操作日志一样是只读页。这里刻意<b>不按用户类型过滤</b>：
 * 登录日志最常见的用途是「某个账号是不是被人试过密码」，
 * 而尝试者可能是后台账号、也可能是小程序端账号 ——
 * 默认只给一半数据，会让人误判"没有异常登录"。用类型标签区分即可。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<SysLogQuery>({
  pageNum: 1,
  pageSize: 10,
  userName: '',
  status: undefined
})

/** 登录时间范围单独存：el-date-picker 的区间绑的是数组，不好直接塞进 query */
const timeRange = ref<[string, string] | undefined>()

const loading = ref(false)
const tableData = ref<SysLoginLogVO[]>([])
const total = ref(0)

async function fetchList() {
  loading.value = true
  try {
    const params: SysLogQuery = {
      ...query,
      startDate: timeRange.value?.[0],
      endDate: timeRange.value?.[1]
    }
    const res = await pageLoginLogs(stripEmpty(params))
    // 数据为空时后端返回 records: []，这里再兜一层 || []，
    // 避免任何字段缺失就让整个表格渲染失败（空数据必须优雅显示）
    tableData.value = res?.records || []
    total.value = res?.total || 0
  } catch (error) {
    console.error('[登录日志] 查询失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
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

/** 登录人类型标签，与后端 CommonConstants.OPERATOR_TYPE_* 一一对应 */
const operatorTypeLabel = (type?: number) =>
  OPERATOR_TYPE_OPTIONS[type === 1 ? 1 : type === 3 ? 3 : 2].label
const operatorTypeTag = (type?: number) =>
  OPERATOR_TYPE_OPTIONS[type === 1 ? 1 : type === 3 ? 3 : 2].tag

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="登录账号">
          <el-input
            v-model="query.userName"
            placeholder="支持模糊匹配"
            clearable
            style="width: 170px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item label="登录时间">
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
        <span class="table-title">登录日志（共 {{ total }} 条）</span>
        <el-button :icon="'Refresh'" @click="fetchList">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column label="登录时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.loginTime) }}</template>
        </el-table-column>

        <el-table-column prop="userName" label="登录账号" min-width="140">
          <template #default="{ row }">
            <span>{{ row.userName || '-' }}</span>
            <el-tag :type="operatorTypeTag(row.userType)" size="small" effect="plain">
              {{ operatorTypeLabel(row.userType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="ip" label="IP" width="130">
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>

        <el-table-column prop="location" label="归属地" width="120">
          <template #default="{ row }">{{ row.location || '-' }}</template>
        </el-table-column>

        <el-table-column prop="browser" label="浏览器 / 客户端" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.browser || '-' }}</template>
        </el-table-column>

        <el-table-column prop="os" label="操作系统" width="130">
          <template #default="{ row }">{{ row.os || '-' }}</template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="msg" label="结果说明" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.msg || '-' }}</template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无登录日志" :image-size="80" />
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
</style>
