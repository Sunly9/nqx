import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'

/**
 * 用户管理接口，对应后端 {@code AdminAppUserController}（`/admin/app-user/**`）。
 *
 * <h3>为什么单独一个文件，不放进 api/system.ts</h3>
 *
 * `system.ts` 管的是**后台管理员**（sys_user）：账号、角色、权限、日志。
 * 而这里管的是**小程序用户**（app_user）：平台上被服务的回收方/采购方。
 *
 * 两者在数据上是完全不同的两张表、两套权限码。放在一起会让
 * "禁用管理员"和"禁用小程序用户"看起来像同一个动作 ——
 * 而它们的影响面完全不同（前者影响后台，后者影响前台）。
 *
 * 类型定义写在本文件里而不新建 `types/user.ts`：这些类型只被这一个页面
 * 与这两个接口使用，和接口函数放在一起，改接口时更容易顺手改类型。
 */

/* ============================== 小程序用户 ============================== */

/**
 * 小程序用户列表项（后端 AdminAppUserVO）。
 *
 * **字段全部是驼峰**：这是管理端新接口，没有历史契约，统一驼峰与
 * 其它管理端 VO（如 SysUserVO）保持一致。
 * 注意不要与本文件外的 AppUserVO（小程序 userInfo，混用下划线）混淆。
 *
 * 后端开启了非空序列化（null 字段不输出），所以除主键与状态外都要可选。
 */
export interface AdminAppUserVO {
  id: number
  nickname?: string
  avatarUrl?: string
  phone?: string
  realName?: string
  studentNo?: string
  college?: string
  labName?: string
  /** 累计回收重量（kg） */
  totalWeight?: number
  /** 累计碳减排（kg） */
  carbonSaved?: number
  /** 状态 0禁用 1正常 */
  status: number
  /** 当前积分余额 */
  points?: number
  lastLoginTime?: string
  createTime?: string
}

/** 检索条件。所有字段可选，后端会跳过空条件 */
export interface AppUserQuery {
  nickname?: string
  phone?: string
  college?: string
  status?: number
  /** 注册时间起，YYYY-MM-DD */
  startDate?: string
  /** 注册时间止，YYYY-MM-DD（含当天） */
  endDate?: string
  pageNum?: number
  pageSize?: number
}

/** 小程序用户分页 */
export function pageAppUser(params: AppUserQuery) {
  return http.get<PageResult<AdminAppUserVO>>('/admin/app-user/page', params)
}

/**
 * 启用 / 禁用小程序用户。
 *
 * `status` 走查询参数（与后端 `@RequestParam` 一致），不是请求体。
 * 禁用会同时注销该用户的会话，使其立刻失效 —— 否则旧 token 在
 * 有效期内仍然可用，相当于"禁用"没有真正生效。
 */
export function changeAppUserStatus(id: number, status: number) {
  return http.put<void>(`/admin/app-user/${id}/status`, undefined, {
    params: { status }
  })
}
