import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types/api'
import { getToken, triggerUnauthorized } from './auth'

/**
 * Axios 封装。
 *
 * 两个关键约定：
 * 1. baseURL 留空，请求路径直接写 `/admin/...`。开发期由 Vite proxy 转发，
 *    生产期由 Nginx 反代，前端代码两边通用。
 * 2. 后端 HTTP 状态码恒为 200，业务结果在 body 的 code 里 ——
 *    所以「成功/失败」的判断必须放在响应拦截器里看 code，
 *    而不是依赖 axios 自身的 reject 分支（它只在网络层错误时才触发）。
 */
const service = axios.create({
  baseURL: '',
  timeout: 20000
})

service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

service.interceptors.response.use(
  // 返回类型显式写成 any：拦截器把「统一返回体」拆成了 data，
  // 这与 axios 声明的 AxiosResponse 返回类型不符，是这一层刻意为之的类型逃逸。
  // 逃逸只发生在这一个函数里，业务侧拿到的仍然是下面的 http.get<T> 泛型类型。
  (response: AxiosResponse<ApiResult>): any => {
    const res = response.data

    // 文件下载等非 JSON 响应（blob）直接透传，不做 code 判断
    if (!res || typeof res !== 'object' || !('code' in res)) {
      return response.data
    }

    if (res.code === 200) {
      // 直接把 data 抛出去，业务代码就不用每次都写 res.data.data
      return res.data
    }

    if (res.code === 401) {
      ElMessage.error(res.msg || '登录已过期，请重新登录')
      triggerUnauthorized()
      return Promise.reject(new Error(res.msg || '登录已过期'))
    }

    ElMessage.error(res.msg || '操作失败')
    return Promise.reject(new Error(res.msg || '操作失败'))
  },
  (error): any => {
    // 能进到这里说明是网络层/框架级错误，后端根本没返回统一返回体
    const status = error?.response?.status
    let message = error?.message || '网络异常'
    if (message.includes('timeout')) {
      message = '请求超时，请检查后端服务是否正常'
    } else if (status === 404) {
      message = '接口不存在（404）'
    } else if (status >= 500) {
      message = `服务器错误（${status}）`
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

/**
 * 给调用方用的类型化快捷方法。
 *
 * 拦截器已经把响应体拆成了 `data`，但 axios 的类型仍然认为返回 AxiosResponse，
 * 所以这里用 as 收回正确类型 —— 类型体操只做这一层，不往业务代码里渗透。
 */
export const http = {
  // params 声明成 object 而不是 Record<string, unknown>：
  // 后者要求调用方传的对象带索引签名，而业务里的 Query 接口（ProductQuery 等）
  // 都是显式字段、没有索引签名，会逼着每个调用点写一次 as Record<string, unknown>。
  get<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, { params, ...config }) as unknown as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.put(url, data, config) as unknown as Promise<T>
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete(url, config) as unknown as Promise<T>
  }
}

export default service
