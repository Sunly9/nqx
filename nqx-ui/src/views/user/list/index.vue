<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeAppUserStatus, pageAppUser } from '@/api/user'
import type { AdminAppUserVO, AppUserQuery } from '@/api/user'
import { formatDateTime, formatWeight, stripEmpty } from '@/utils/format'

/**
 * 用户管理 / 小程序用户。
 *
 * <p>这里是**平台服务的用户**（回收方 / 采购方），不是后台管理员。
 * 后台管理员在「系统管理 → 管理员管理」里，那是另一张表、另一套权限。
 *
 * <h3>这个页面为什么重要</h3>
 *
 * 在此之前，后台完全看不到小程序用户。带来的实际问题是：
 * 用户反馈订单问题时运营查不了他的单；遇到刷单账号没有任何限制手段；
 * 「这个月新增多少人」这类问题无从回答。数据一直在 `app_user` 里，缺的只是入口。
 *
 * <h3>禁用为什么要二次确认</h3>
 *
 * 禁用会**同时注销该用户的登录会话**，操作后他立刻掉线且无法再登录，
 * 直到被启用。这是不可撤销的一步（虽然可以再启用，但用户已经受到影响），
 * 所以必须明确告知后果，而不是点一下就执行。
 */

/* ------------------------------ 检索条件 ------------------------------ */

const query = reactive<AppUserQuery>({
  nickname: undefined,
  phone: undefined,
  college: undefined,
  status: undefined,
  startDate: undefined,
  endDate: undefined
})

/** 注册时间区间（Element Plus 的日期范围选择器用一个数组承载） */
const dateRange = ref<[string, string] | null>(null)

const loading = ref(false)
const tableData = ref<AdminAppUserVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

/* ------------------------------ 数据加载 ------------------------------ */

async function fetchList() {
  loading.value = true
  try {
    // 日期区间拆成后端要的两个字段；空值由 stripEmpty 去掉，避免传 undefined 变成字符串
    const params: AppUserQuery = stripEmpty({
      ...query,
      startDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1],
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    const res = await pageAppUser(params)
    tableData.value = res.records ?? []
    total.value = res.total ?? 0
  } catch (error) {
    console.error('[小程序用户] 加载失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNum.value = 1
  fetchList()
}

function handleReset() {
  query.nickname = undefined
  query.phone = undefined
  query.college = undefined
  query.status = undefined
  dateRange.value = null
  pageNum.value = 1
  fetchList()
}

function handlePageChange(p: number) {
  pageNum.value = p
  fetchList()
}

function handleSizeChange(s: number) {
  pageSize.value = s
  pageNum.value = 1
  fetchList()
}

/* ------------------------------ 启用 / 禁用 ------------------------------ */

/**
 * 切换用户状态。
 *
 * 启用可以直接执行（影响是正面的：让用户恢复使用）。
 * 禁用必须二次确认并说清后果 —— 它会立刻把用户踢下线。
 */
async function handleToggleStatus(row: AdminAppUserVO) {
  const nextStatus = row.status === 1 ? 0 : 1
  const name = row.nickname || `用户 #${row.id}`

  if (nextStatus === 0) {
    try {
      await ElMessageBox.confirm(
        `确认禁用「${name}」？\n` +
          '禁用后该用户会立刻退出登录，且在重新启用前无法登录小程序。\n' +
          '他的历史订单、积分、地址都不会被删除。',
        '禁用用户',
        { type: 'warning', confirmButtonText: '确认禁用', cancelButtonText: '取消' }
      )
    } catch {
      return // 用户取消
    }
  }

  try {
    await changeAppUserStatus(row.id, nextStatus)
    ElMessage.success(nextStatus === 1 ? '已启用' : '已禁用，该用户已退出登录')
    fetchList()
  } catch (error) {
    console.error('[小程序用户] 修改状态失败', error)
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="page-container">
    <!-- 检索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" label-width="80px" inline @submit.prevent>
        <el-form-item label="昵称">
          <el-input v-model="query.nickname" placeholder="模糊搜索" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="query.phone" placeholder="模糊搜索" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="学院">
          <el-input v-model="query.college" placeholder="模糊搜索" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" :value="1" />
            <el-option label="已禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册时间">
          <!--
            用日期区间而不是两个独立输入框：注册时间筛选几乎总是成对使用，
            两个框容易漏填一个，而后端只给 startDate 时语义会变成"某天之后"，
            往往不是使用者想要的结果。
          -->
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            range-separator="至"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="'Search'" @click="handleSearch">查询</el-button>
          <el-button :icon="'RefreshLeft'" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 数据表 -->
    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column label="用户" min-width="180">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="32" :src="row.avatarUrl || undefined">
                {{ (row.nickname || '?').slice(0, 1) }}
              </el-avatar>
              <div class="user-meta">
                <div class="user-name">{{ row.nickname || '未设置昵称' }}</div>
                <div class="user-id">ID: {{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="130">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>

        <el-table-column label="学院 / 实验室" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.college || row.labName">
              {{ row.college || '-' }}
              <span v-if="row.labName" class="sub-text"> / {{ row.labName }}</span>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column label="累计回收" width="110" align="right">
          <template #default="{ row }">{{ formatWeight(row.totalWeight) }}</template>
        </el-table-column>

        <el-table-column label="碳减排" width="110" align="right">
          <template #default="{ row }">{{ formatWeight(row.carbonSaved) }}</template>
        </el-table-column>

        <el-table-column label="积分余额" width="100" align="right">
          <template #default="{ row }">{{ row.points ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '正常' : '已禁用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="最后登录" width="160">
          <template #default="{ row }">
            {{ row.lastLoginTime ? formatDateTime(row.lastLoginTime) : '从未登录' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-permission="'app:user:edit'"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pagination"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </el-card>
  </div>
</template>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card :deep(.el-form-item) {
  margin-bottom: 12px;
}

.table-card {
  min-height: 320px;
}

/* 头像 + 昵称 + ID 的紧凑排列 */
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.user-name {
  font-size: 13px;
  color: #303133;
}

.user-id {
  font-size: 12px;
  color: #909399;
}

.sub-text {
  color: #909399;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
