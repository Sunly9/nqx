/**
 * 展示层的格式化工具。
 *
 * 后端金额一律用「分」传输（整数运算无浮点误差），
 * 只在渲染这一刻才换算成「元」——换算逻辑集中在这里，
 * 避免每个页面各写一遍 `/100` 而漏掉其中几处。
 */

/** 分 → 元字符串，保留两位小数。null/undefined 返回 '-' */
export function fenToYuan(fen?: number | null): string {
  if (fen === null || fen === undefined) return '-'
  return (fen / 100).toFixed(2)
}

/** 元 → 分。用于表单里用户输入的元换算成提交值 */
export function yuanToFen(yuan?: number | string | null): number {
  if (yuan === null || yuan === undefined || yuan === '') return 0
  const n = Number(yuan)
  if (Number.isNaN(n)) return 0
  // 先放大再取整，规避 0.1 * 100 = 10.000000000000002 这类浮点误差
  return Math.round(n * 100)
}

/** 时间字符串统一成 `YYYY-MM-DD HH:mm:ss` 展示 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

/** 只保留日期 */
export function formatDate(value?: string | null): string {
  if (!value) return '-'
  return value.slice(0, 10)
}

/** 重量展示，去掉无意义的尾随 0 */
export function formatWeight(kg?: number | null): string {
  if (kg === null || kg === undefined) return '-'
  return `${Number(kg)} kg`
}

/**
 * 剔除查询条件里的空值。
 *
 * 检索表单里未填的字段都是 '' 或 undefined。若原样发给后端，
 * 整数型参数（status、categoryId 等）会收到空字符串，
 * Spring 虽然多数情况下能转成 null，但依赖框架的隐式行为不如前端先清干净 ——
 * 尤其是 `minPrice=0` 这种合法的 0 值不能被误删，因此只剔除 '' 与 null/undefined。
 */
export function stripEmpty<T extends object>(obj: T): T {
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(obj)) {
    if (value === '' || value === null || value === undefined) continue
    result[key] = value
  }
  // 返回类型写成 T 而不是 Record<string, unknown>：
  // 检索条件里真正必填的只有 pageNum/pageSize，而它们永远有值不会被剔除，
  // 因此这里返回的对象在类型与事实两个层面都仍然是一个合法的 T，
  // 这样调用方就不用为了喂饱类型检查再多写一次 as。
  return result as T
}

/**
 * 生成幂等键。
 *
 * 优先用 crypto.randomUUID；旧浏览器没有该 API 时退化成时间戳 + 随机串，
 * 这里不需要密码学强度，只需要「同一时刻两次点击不撞车」。
 */
export function genRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
