import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'

/**
 * 问答接口，对应后端 AdminQuestionController / AdminQuestionAnswerController。
 *
 * 类型定义放在本文件而不是 types/ 目录下：问答题库的契约只被这两个页面消费，
 * 放在接口文件旁边能让"字段名与接口"始终一起改，
 * 不必在两个目录之间来回跳（跨目录的字段对不上正是第 26 条踩坑的形态）。
 */

/* ------------------------------ 类型定义 ------------------------------ */

/**
 * 题库列表项（后端直接返回 Question 实体）。
 *
 * 列表 SQL 刻意不查 `options`，所以这里的 `options` 是<b>可选</b>的 ——
 * 编辑弹窗必须另外调 getQuestion(id) 拿完整选项再回填。
 */
export interface QuestionVO {
  id: number
  content: string
  /** 1单选 2多选 3判断 */
  questionType?: number
  /** 选项 JSON 字符串，列表接口不返回 */
  options?: string
  correctAnswer?: string
  analysis?: string
  points?: number
  /** 1易 2中 3难 */
  difficulty?: number
  publishDate?: string
  /** 0草稿 1已发布 2已下线 */
  status: number
  sort?: number
  createTime?: string
  updateTime?: string
}

/** 题目详情（后端 QuestionDetailVO），比列表项多出 questionTypeText 与 options */
export interface QuestionDetailVO extends QuestionVO {
  questionTypeText?: string
}

/** 题库检索条件 */
export interface QuestionQuery {
  pageNum: number
  pageSize: number
  content?: string
  questionType?: number
  difficulty?: number
  status?: number
  startDate?: string
  endDate?: string
}

/**
 * 题目新增/修改入参。
 *
 * `options` 传 JSON 字符串而不是数组：后端那一列本身就是 JSON 文本，
 * 由后端统一做"合法性 + 正确答案必须落在选项 key 里"的校验，
 * 前端只负责把用户填的选项序列化成同一种格式。
 */
export interface QuestionSaveDTO {
  id?: number
  content: string
  questionType?: number
  options: string
  correctAnswer: string
  analysis?: string
  points: number
  difficulty?: number
  publishDate?: string
  status?: number
  sort?: number
}

/** 选项元素，与后端解析的 JSON 结构一致 */
export interface QuestionOption {
  key: string
  value: string
}

/** 答题记录列表项（后端 AdminUserAnswerVO，含 JOIN 出来的昵称与题干） */
export interface UserAnswerVO {
  id: number
  userId: number
  nickname?: string
  questionId: number
  /** 题干，题目被逻辑删除时取不到 */
  questionContent?: string
  /** 该题正确答案，供核对判定是否公平 */
  correctAnswer?: string
  questionDate?: string
  userAnswer?: string
  /** 0否 1是 */
  isCorrect: number
  gainPoints?: number
  costTime?: number
  createTime?: string
}

/** 答题记录检索条件 */
export interface AdminAnswerQuery {
  /*
   * 分页参数可选：后端有默认值（1 / 10）。导出时复用同一个查询对象但不带分页，
   * 声明成必填就会逼着导出代码塞假值。详见 types/order.ts 的同类说明。
   */
  pageNum?: number
  pageSize?: number
  nickname?: string
  userId?: number
  questionId?: number
  isCorrect?: number
  startDate?: string
  endDate?: string
}

/* ------------------------------ 题库 ------------------------------ */

export function pageQuestion(params: QuestionQuery) {
  return http.get<PageResult<QuestionVO>>('/admin/question/page', params)
}

/** 题目详情。列表不带 options，编辑回填靠它 */
export function getQuestion(id: number) {
  return http.get<QuestionDetailVO>(`/admin/question/${id}`)
}

export function createQuestion(data: QuestionSaveDTO) {
  return http.post<number>('/admin/question', data)
}

export function updateQuestion(data: QuestionSaveDTO) {
  return http.put<void>('/admin/question', data)
}

export function deleteQuestion(id: number) {
  return http.delete<void>(`/admin/question/${id}`)
}

/** 发布 / 下线。后端把 status 设计成 query 参数而非 body，这里要跟着走 */
export function changeQuestionStatus(id: number, status: number) {
  return http.put<void>(`/admin/question/${id}/status`, undefined, { params: { status } })
}

/* ------------------------------ 答题记录 ------------------------------ */

export function pageUserAnswer(params: AdminAnswerQuery) {
  return http.get<PageResult<UserAnswerVO>>('/admin/question/answer/page', params)
}

/**
 * 导出答题记录（Excel）。
 *
 * **返回的是下载地址，不是文件内容。** 后端「生成 xlsx → 存对象存储 → 返回链接」，
 * 因为微信云托管对返回包有 1000KiB 上限，直接吐文件会被截断。
 */
export function exportUserAnswer(params: AdminAnswerQuery) {
  return http.get<string>('/admin/question/answer/export', params)
}

/* ------------------------------ 选项工具 ------------------------------ */

/** 选项 key 的字母表。超过 26 个选项的题目本身就不合理，不给出路 */
const OPTION_KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

/**
 * 把后端的 options JSON 字符串解析成选项数组。
 *
 * 解析失败时返回空数组而不是抛异常：这是一次"回填"，
 * 让弹窗打开后是一个空选项列表、由管理员自行重填，
 * 比整个弹窗打不开、只能看到控制台报错要好处理得多。
 */
export function parseOptions(json?: string): QuestionOption[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item) => item && typeof item.key === 'string')
      .map((item) => ({ key: String(item.key), value: String(item.value ?? '') }))
  } catch {
    return []
  }
}

/** 取第 index 个选项的 key（0 → A）。超过 26 个时返回 '?' 占位，不会静默串位 */
export function optionKeyAt(index: number): string {
  return OPTION_KEYS[index] ?? '?'
}
