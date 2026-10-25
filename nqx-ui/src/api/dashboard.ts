import { http } from '@/utils/request'

/**
 * 数据看板接口，对应后端 AdminDashboardController（统一权限码 dashboard:view）。
 *
 * 五个接口都是只读聚合统计，返回值直接来自 SQL 聚合函数，因此有两点必须提前说明：
 * 1. SUM 落在空表上会得到 null，MySQL 的 DECIMAL 又会被序列化成字符串（"2.20"），
 *    所以下面的类型统一声明成 `number | string | null`，由页面侧统一转数字 ——
 *    在这里硬写成 number 只是自欺欺人，运行时该来的字符串照样会来。
 * 2. 字段全部可选：后端开了非空序列化的话，null 字段会直接从 JSON 里消失。
 */

/** 总览指标（GET /admin/dashboard/overview） */
export interface DashboardOverview {
  /** 用户数 */
  userCount?: number | string | null
  /** 订单总数 */
  orderCount?: number | string | null
  /** 回收总重，单位 kg */
  recycleWeight?: number | string | null
  /** 碳减排，单位 kg */
  carbonSaved?: number | string | null
  /** 已发放积分 */
  pointsIssued?: number | string | null
  /** 商品数 */
  productCount?: number | string | null
}

/** 回收量趋势中的一天。statDate 是 LocalDate 序列化出的 "2026-10-15" */
export interface RecycleTrendItem {
  statDate?: string | null
  /** 当日完成单量 */
  orderCount?: number | string | null
  /** 当日回收重量，单位 kg */
  totalWeight?: number | string | null
}

/** 积分趋势中的一天 */
export interface PointsTrendItem {
  statDate?: string | null
  /** 当日发放（正变动） */
  earn?: number | string | null
  /** 当日消耗（负变动取绝对值） */
  spend?: number | string | null
}

/**
 * 订单状态分布的一行，按 (status, orderType) 分组。
 *
 * statusText 由后端 OrderStatusEnum 翻译好，前端直接展示即可 ——
 * 前端再维护一份状态码→文案的映射，就会出现两处文案不一致的隐患。
 */
export interface OrderStatusStat {
  /** 1=回收单 2=采购单 */
  orderType?: number | null
  status?: number | null
  statusText?: string | null
  total?: number | string | null
}

/** 热门商品（按下单件数排序） */
export interface TopProductItem {
  productName?: string | null
  /** 下单笔数 */
  orderCount?: number | string | null
  /** 下单件数 */
  totalQuantity?: number | string | null
}

/** 总览指标 */
export function getDashboardOverview() {
  return http.get<DashboardOverview>('/admin/dashboard/overview')
}

/** 回收量趋势，days 由后端归一化（上限 180，非法值回退 30） */
export function getRecycleTrend(days: number) {
  return http.get<RecycleTrendItem[]>('/admin/dashboard/recycle-trend', { days })
}

/** 订单状态分布，不分页，返回全部状态组合 */
export function getOrderStatus() {
  return http.get<OrderStatusStat[]>('/admin/dashboard/order-status')
}

/** 积分发放/消耗趋势 */
export function getPointsTrend(days: number) {
  return http.get<PointsTrendItem[]>('/admin/dashboard/points-trend', { days })
}

/** 热门商品 Top N，后端把 limit 夹在 [1, 50] */
export function getTopProduct(limit = 10) {
  return http.get<TopProductItem[]>('/admin/dashboard/top-product', { limit })
}
