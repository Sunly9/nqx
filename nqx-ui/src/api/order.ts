import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { AdminOrderQuery, AdminOrderVO, ConfirmRecycleDTO } from '@/types/order'

/** 订单接口，对应后端 AdminOrderController */

export function pageOrder(params: AdminOrderQuery) {
  return http.get<PageResult<AdminOrderVO>>('/admin/order/page', params)
}

/**
 * 导出订单（Excel）。
 *
 * <p><b>返回的是下载地址，不是文件内容。</b>后端「生成 xlsx → 存对象存储 → 返回链接」，
 * 因为微信云托管对返回包有 1000KiB 上限，直接吐文件会在数据量稍大时被截断
 * （前端拿到半个 xlsx，Excel 提示"文件已损坏"）。
 *
 * <p>筛选条件与 {@link pageOrder} 完全一致 —— 后端用的是同一个 query 对象
 * 和同一套动态 SQL，所以"列表里筛出什么，导出就是什么"。
 */
export function exportOrders(params: AdminOrderQuery) {
  return http.get<string>('/admin/order/export', params)
}

/** 审核通过（回收单：待审核 → 待回收） */
export function auditOrder(orderNo: string, remark?: string) {
  return http.post<void>(`/admin/order/${orderNo}/audit`, undefined, { params: { remark } })
}

/** 驳回（回收单），reason 后端标记为必填 */
export function rejectOrder(orderNo: string, reason: string) {
  return http.post<void>(`/admin/order/${orderNo}/reject`, undefined, { params: { reason } })
}

/**
 * 确认回收：录入实际重量，可选同时结算发积分。
 *
 * body 字段是下划线/别名形式（actual_weight / audit_remark），
 * 由后端 DTO 上的 @JsonProperty 决定，不能想当然写成驼峰。
 */
export function confirmRecycle(orderNo: string, data: ConfirmRecycleDTO) {
  return http.post<unknown>(`/admin/order/${orderNo}/confirm-recycle`, data)
}

/**
 * 单独结算回收单（待结算 12 → 已完成 13）。
 *
 * <p><b>为什么需要这个接口</b>：回收流程有「先确认回收、后结算」的两步走法 ——
 * 工作人员上门称重后先点「确认回收」（录入实际重量，订单进入 `待结算`），
 * 积分可以稍后再发。但管理端页面原来**只接了「确认回收」这一个按钮**，
 * 订单停在 `待结算` 之后就**没有任何后续操作入口**了：
 * 按钮不显示、流程走不完，而积分其实一直没发。
 *
 * <p>后端 `POST /admin/order/{orderNo}/settle` 一直是有的（权限码同样是
 * `order:recycle:confirm`），只是前端没接 —— 接口覆盖度审计脚本
 * `scripts/audit-api-coverage.js` 把它列在「后端有、前端没接」里。
 *
 * <p>remark 走查询参数（与后端 `@RequestParam` 一致），不是请求体。
 */
export function settleOrder(orderNo: string, remark?: string) {
  return http.post<unknown>(`/admin/order/${orderNo}/settle`, undefined, {
    params: { remark }
  })
}

/**
 * 采购单发货（待发货 → 待收货）。
 *
 * <p><b>为什么必须把 courierNo 传出去</b>：后端
 * {@code POST /admin/order/{orderNo}/deliver} 是接受 {@code courierNo} 查询参数的
 * （见 AdminOrderController），而且快递单号要落库给小程序端展示物流信息。
 *
 * <p>原来这个方法只传了订单号，于是管理端弹窗里"请填写快递单号"的必填校验
 * 形同虚设 —— 管理员老老实实填了单号，**提交时被静默丢弃**，
 * 用户在小程序里永远看不到物流单号，而前后端都没有任何报错。
 *
 * <p>参考同文件里 audit / reject 的写法：路径参数在 URL 上，
 * 其余参数走 {@code params}。
 */
export function deliverOrder(orderNo: string, courierNo?: string) {
  return http.post<void>(`/admin/order/${orderNo}/deliver`, undefined, {
    params: { courierNo }
  })
}
