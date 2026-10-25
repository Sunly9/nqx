import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { PointsAdjustDTO } from '@/types/order'

/**
 * 积分接口，对应后端 AdminPointsController。
 *
 * 字段名一律以<b>后端实际返回</b>为准（已用 curl 逐个核对），不是按语义猜的：
 * 账户返回的是 `totalEarn` / `totalSpend`，流水返回的是 `changeValue` / `balanceAfter`。
 * 之前按语义先行约定过 `totalEarned` / `points` / `balance` 这一套名字，
 * 与后端对不上就会表现为"这一列永远空白，页面也不报错"——
 * 属于最难发现的一类问题（见 docs/06-踩坑记录.md 第 26、27 条）。
 */

/* ------------------------------ 类型定义 ------------------------------ */

/**
 * 积分账户列表项（后端 AdminPointsAccountVO）。
 *
 * `nickname` / `avatarUrl` 来自 LEFT JOIN app_user，用户被逻辑删除时取不到，
 * 加上后端开启了 null 字段不序列化，这两个字段必须声明为可选。
 */
export interface PointsAccountVO {
  id: number
  userId: number
  nickname?: string
  avatarUrl?: string
  /** 当前可用积分 */
  balance: number
  /** 累计获得 */
  totalEarn: number
  /** 累计消耗 */
  totalSpend: number
  createTime?: string
  updateTime?: string
}

/** 积分流水列表项（后端 AdminPointsRecordVO） */
export interface PointsRecordVO {
  id: number
  userId: number
  nickname?: string
  /** 变动类型，见 CHANGE_TYPE_OPTIONS */
  changeType: number
  /** 变动类型中文，由后端枚举补齐 */
  changeTypeText?: string
  /** 带符号的变动值：正数增加、负数扣减 */
  changeValue: number
  /** 变动后余额 */
  balanceAfter?: number
  /** 关联业务单号（幂等键也落在这里） */
  bizNo?: string
  remark?: string
  createTime?: string
}

/**
 * 积分检索条件。
 *
 * 账户与流水共用同一个查询对象（后端也是同一个 AdminPointsQuery）：
 * 两者的筛选条件几乎完全重合，只有 changeType / bizNo 是流水专有。
 * 日期用 `LocalDate`（YYYY-MM-DD），后端会按下闭区间处理，
 * 选了截止日就包含当天全天。
 */
export interface AdminPointsQuery {
  /*
   * 分页参数可选：后端有默认值（1 / 10）。导出时复用同一个查询对象但不带分页，
   * 声明成必填就会逼着导出代码塞假值。详见 types/order.ts 的同类说明。
   */
  pageNum?: number
  pageSize?: number
  userId?: number
  nickname?: string
  changeType?: number
  bizNo?: string
  startDate?: string
  endDate?: string
}

/**
 * 积分变动类型下拉项。
 *
 * 与后端 PointsChangeTypeEnum 一一对应，值直接用枚举 code。
 * 刻意不在前端做 code → 文案的映射：列表里的中文由后端下发（changeTypeText），
 * 前端这份只用于筛选下拉框，避免同一套文案在两端各维护一份而改漏一处。
 */
export const CHANGE_TYPE_OPTIONS = [
  { label: '注册奖励', value: 1 },
  { label: '每日问答', value: 2 },
  { label: '回收返积分', value: 3 },
  { label: '积分兑换', value: 4 },
  { label: '兑换退回', value: 5 },
  { label: '管理员调整', value: 6 },
  { label: '采购订单奖励', value: 7 }
] as const

/* ------------------------------ 接口 ------------------------------ */

export function pagePointsAccount(params: AdminPointsQuery) {
  return http.get<PageResult<PointsAccountVO>>('/admin/points/account/page', params)
}

export function pagePointsRecord(params: AdminPointsQuery) {
  return http.get<PageResult<PointsRecordVO>>('/admin/points/record/page', params)
}

/**
 * 导出积分流水（Excel）。
 *
 * **返回的是下载地址，不是文件内容。** 后端「生成 xlsx → 存对象存储 → 返回链接」，
 * 因为微信云托管对返回包有 1000KiB 上限，直接吐文件会被截断。
 */
export function exportPointsRecord(params: AdminPointsQuery) {
  return http.get<string>('/admin/points/record/export', params)
}

/** 调整积分。`points` 为有符号数：正数加分、负数扣分，不能为 0 */
export function adjustPoints(data: PointsAdjustDTO) {
  return http.post<number>('/admin/points/adjust', data)
}
