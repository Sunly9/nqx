/** 订单与积分相关类型定义 */

/** 后台订单列表项（AdminOrderVO） */
export interface AdminOrderVO {
  id: number
  orderNo: string
  userId: number
  nickname?: string
  /** 1回收 2采购 */
  orderType: number
  orderTypeText?: string
  /** 回收方式 send(邮寄) / pickup(上门) */
  recycleMethod?: string
  status: number
  statusText?: string
  /** 预估重量（kg）。后端非空序列化，null 时字段不出现 */
  estimatedWeight?: number
  /** 实际重量（kg） */
  actualWeight?: number
  totalPoints?: number
  /** 碳减排量（kg） */
  carbonSaved?: number
  contactName?: string
  contactPhone?: string
  courierNo?: string
  /**
   * 买家最近一次「提醒发货」的时间。
   *
   * 这是买家在催，不是发货本身 —— 管理员看到它就知道该优先处理这单。
   */
  remindShipAt?: string
  /** 买家累计提醒发货次数 */
  remindShipCount?: number
  /**
   * 后台展示文案，由后端拼好（如「催 2 次 · 10-21 16:42」）。
   *
   * 不要在前端自己拼：拼接规则只应该有一处，否则列表、导出、详情
   * 三处各写一遍，迟早不一致。
   */
  remindShipText?: string
  createTime?: string
  finishTime?: string
}

/** 后台订单检索条件 */
export interface AdminOrderQuery {
  /*
   * 分页参数声明成可选：后端 `AdminOrderQuery.safePageNum()/safePageSize()`
   * 对空值有默认值（1 / 10），也就是说它们本来就不是必填的。
   *
   * 改成可选是为了让**导出**能复用同一个查询对象而不带分页 ——
   * 导出走的是 `new Page<>(1, maxRows, false)`，分页参数会被忽略。
   * 如果这里声明成必填，导出代码就得塞两个假值进去，反而误导读者。
   */
  pageNum?: number
  pageSize?: number
  orderNo?: string
  nickname?: string
  userId?: number
  orderType?: number
  status?: number
  recycleMethod?: string
  /** 后端是 LocalDateTime，Spring 默认按 ISO 解析，因此传 'YYYY-MM-DDTHH:mm:ss' */
  startTime?: string
  endTime?: string
  minPoints?: number
  maxPoints?: number
}

/**
 * 确认回收入参。
 *
 * 注意备注字段名是 `audit_remark`（后端 DTO 上标的 @JsonProperty），
 * 不是直觉上的 `remark` —— 这里若写错，备注会被静默丢弃。
 */
export interface ConfirmRecycleDTO {
  actual_weight: number
  /** 是否同时结算发放积分，后端默认 true */
  settle?: boolean
  audit_remark?: string
}

/**
 * 管理员调整积分入参。
 *
 * `user_id` / `request_id` 用的是下划线命名，同样是后端 @JsonProperty 的结果；
 * `points` 为有符号数：正数加分、负数扣分。
 */
export interface PointsAdjustDTO {
  user_id: number
  points: number
  remark: string
  /** 幂等键，前端每次操作生成一个 UUID，重试时沿用 */
  request_id: string
}

/**
 * 积分账户与积分流水的类型**已移到 `api/points.ts`**，这里不再定义。
 *
 * ⚠️ 这里曾经放着两个"先行约定"的类型（`PointsAccountVO` / `PointsRecordVO`），
 *    字段名是按 `points_record` 表的语义**猜的**：
 *
 *        猜的                    后端实际的
 *        totalEarned      →      totalEarn
 *        totalUsed        →      totalSpend
 *        points           →      changeValue
 *        balance          →      balanceAfter
 *        bizType          →      changeType
 *        bizTypeText      →      changeTypeText
 *
 *    **一个都对不上。** 而 TypeScript 只做编译期检查、运行时字段缺失不报错，
 *    所以照着这套类型写出来的页面会：编译通过、接口返回 200、
 *    页面上那些列全是空白 —— 正是 docs/06 第 26/27 条那类"不报错的空白"。
 *
 *    它们当时确实没人引用（属于死代码），但死代码的危害恰恰在这里：
 *    下一个人要写积分页面时，最自然的动作就是复用这两个"现成"的类型。
 *    所以宁可删掉并留下这段说明，也不留一个会误导人的"方便"。
 *
 *    真正的类型在 `src/api/points.ts`，那里的字段名是**逐个对照接口实测返回写的**。
 */

