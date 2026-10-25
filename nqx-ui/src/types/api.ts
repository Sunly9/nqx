/**
 * 后端统一返回体与分页结构。
 *
 * 后端无论成功失败，HTTP 状态码恒为 200，业务结果全在 `code` 里 ——
 * 因此前端判断成功与否只能看 `code`，绝不能依赖 axios 的 catch 分支。
 */

/** 统一返回体：所有接口都长这样 */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  timestamp: number
  /** 后端 Result 里带的冗余布尔字段，前端不用，仅为对齐契约保留 */
  success?: boolean
}

/** 分页返回体 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

/** 分页请求的公共参数 */
export interface PageQuery {
  pageNum: number
  pageSize: number
}
