import { http } from '@/utils/request'

/**
 * 文件上传。
 *
 * 后端契约：POST /admin/file/upload，multipart/form-data，字段名固定为 `file`，
 * data 直接是文件访问 URL 字符串。
 * 注意后端还有个可选参数 bizType（默认 common），这里透出以便分类存储。
 */
export function uploadFile(file: File, bizType = 'common') {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  return http.post<string>('/admin/file/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
