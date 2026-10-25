import { ElMessage } from 'element-plus'

/**
 * 导出文件的下载工具。
 *
 * ============================================================================
 *  【为什么改成"先拿链接，再下载"】
 * ============================================================================
 *
 * 最初的做法是后端直接把 xlsx 写进 HTTP 响应流（`responseType: 'blob'`）。
 * 本地跑没问题，但部署到**微信云托管**之后会必然失败：
 * 云托管对返回包有 **1000KiB 上限**，订单导出一多，响应就被截断 ——
 * 前端拿到的是半个 xlsx，Excel 打开提示"文件已损坏"。
 *
 * 而且这个失败方式特别糟：浏览器显示"下载完成"，用户打开才发现坏了。
 *
 * 现在后端改成：
 *     生成 xlsx → 存对象存储（或本地） → 返回一个下载链接
 * 接口响应只有几十字节，彻底绕开大小限制；文件走静态资源/COS，不占应用带宽。
 *
 * ============================================================================
 *  【跨域时文件名会丢，这是浏览器的限制】
 * ============================================================================
 *
 * `<a download="xxx.xlsx">` 的 `download` 属性**只对同源生效**。
 * 本地模式下地址是 `/files/export/...`（同源），文件名能生效；
 * COS 模式下是 `https://<bucket>.cos...`（跨域），`download` 被忽略。
 *
 * 所以后端的对象 key 里带上了有意义的名字（`订单列表_20261020153012.xlsx`），
 * 跨域下载下来也不是一串 UUID。
 */

/**
 * 触发浏览器下载一个地址。
 *
 * @param url      文件地址（相对或绝对）
 * @param fileName 期望的文件名（同源时生效）
 */
function triggerDownload(url: string, fileName: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  /*
   * 加 target=_blank 的原因：跨域时 `download` 会被忽略，
   * 此时浏览器会**导航**到该地址。若不加 target，当前页面会被替换掉，
   * 用户会以为"点导出把系统点没了"。
   * 加 target 之后，跨域场景是开个新标签再下载；同源场景仍按 download 走。
   */
  link.target = '_blank'
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

/** 时间戳，用于拼期望的文件名：20261020-1530 */
function timestamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`
}

/**
 * 执行一次导出：调接口拿到下载地址，然后触发下载。
 *
 * @param request  已经发起的导出请求（**resolve 出的是下载地址字符串**）
 * @param baseName 文件基础名，如「订单列表」
 */
export async function exportAndDownload(
  request: Promise<unknown>,
  baseName: string
): Promise<void> {
  const url = (await request) as string

  if (!url || typeof url !== 'string') {
    // 后端没返回地址说明约定对不上（比如接口还是老的"直接吐文件"实现），
    // 显式报出来，而不是静默什么都不做
    throw new Error('导出失败：后端没有返回下载地址')
  }

  triggerDownload(url, `${baseName}_${timestamp()}.xlsx`)
  ElMessage.success('导出完成，正在下载')
}
