import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type { RecycleCategoryQuery, RecycleCategoryVO } from '@/types/product'

/** 回收品类接口，对应后端 RecycleCategoryController */

export function pageRecycleCategory(params: RecycleCategoryQuery) {
  return http.get<PageResult<RecycleCategoryVO>>('/admin/recycle-category/page', params)
}

export function createRecycleCategory(data: Partial<RecycleCategoryVO>) {
  return http.post<number>('/admin/recycle-category', data)
}

export function updateRecycleCategory(data: Partial<RecycleCategoryVO>) {
  return http.put<void>('/admin/recycle-category', data)
}

export function deleteRecycleCategory(id: number) {
  return http.delete<void>(`/admin/recycle-category/${id}`)
}
