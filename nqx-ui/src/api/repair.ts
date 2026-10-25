import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'

/**
 * 维修工单接口，对应后端 {@code AdminRepairController}（`/admin/repair/**`）。
 *
 * <p>维修流程原先只有小程序端（提交 + 查看自己的申请），后台没有任何入口，
 * 工单永远停在「待处理」。本文件接的就是新补的管理端处理入口。
 *
 * <p>状态定义与后端 `RepairApplyVO.statusText` 完全一致：
 * `1 待处理 → 2 处理中 → 3 已完成`，`1/2 → 4 已驳回`。
 */

/** 状态常量。写成对象而不是散落的数字，避免各处出现魔数 */
export const REPAIR_STATUS = {
  PENDING: 1,
  PROCESSING: 2,
  COMPLETED: 3,
  REJECTED: 4
} as const

/** 状态下拉选项（与后端文案一致） */
export const REPAIR_STATUS_OPTIONS = [
  { label: '待处理', value: REPAIR_STATUS.PENDING },
  { label: '处理中', value: REPAIR_STATUS.PROCESSING },
  { label: '已完成', value: REPAIR_STATUS.COMPLETED },
  { label: '已驳回', value: REPAIR_STATUS.REJECTED }
]

/** 保修判定选项 */
export const WARRANTY_TYPE_OPTIONS = [
  { label: '保修期内（免费）', value: 'warranty' },
  { label: '已过保（需报价）', value: 'paid' }
]

/** 管理端维修工单列表项（后端 AdminRepairApplyVO） */
export interface AdminRepairApplyVO {
  id: number
  applyNo?: string
  userId?: number
  /** 申请人昵称，LEFT JOIN app_user 取得；用户被删时为空但工单不消失 */
  nickname?: string
  orderId?: number
  orderNo?: string
  productId?: number
  productName?: string
  /** 故障描述 */
  faultDesc?: string
  /** 故障图片，已解析成数组 */
  images?: string[]
  contactName?: string
  contactPhone?: string
  address?: string
  status: number
  statusText?: string
  /** warranty / paid / 空（历史数据未判定） */
  warrantyType?: string
  warrantyTypeText?: string
  handleRemark?: string
  handlerId?: number
  handleTime?: string
  create_time?: string
}

/** 检索条件 */
export interface AdminRepairQuery {
  applyNo?: string
  nickname?: string
  productName?: string
  status?: number
  warrantyType?: string
  /** 申请时间起，YYYY-MM-DD */
  startDate?: string
  /** 申请时间止，YYYY-MM-DD（含当天） */
  endDate?: string
  pageNum?: number
  pageSize?: number
}

/** 处理入参。目标状态由调用的接口决定，不由请求体决定 */
export interface RepairHandleDTO {
  remark?: string
}

/** 维修工单分页 */
export function pageRepair(params: AdminRepairQuery) {
  return http.get<PageResult<AdminRepairApplyVO>>('/admin/repair/page', params)
}

/** 工单详情 */
export function getRepair(id: number) {
  return http.get<AdminRepairApplyVO>(`/admin/repair/${id}`)
}

/** 受理：待处理 → 处理中 */
export function acceptRepair(id: number, remark?: string) {
  return http.post<void>(`/admin/repair/${id}/accept`, { remark })
}

/** 完成：处理中 → 已完成 */
export function completeRepair(id: number, remark?: string) {
  return http.post<void>(`/admin/repair/${id}/complete`, { remark })
}

/** 驳回：待处理/处理中 → 已驳回。**remark 必填**，后端会校验 */
export function rejectRepair(id: number, remark: string) {
  return http.post<void>(`/admin/repair/${id}/reject`, { remark })
}
