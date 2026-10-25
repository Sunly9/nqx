import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  CategoryVO,
  ProductDetailVO,
  ProductQuery,
  ProductSaveDTO,
  ProductVO
} from '@/types/product'

/** 商品接口，对应后端 ProductController / ProductCategoryController */

export function pageProduct(params: ProductQuery) {
  return http.get<PageResult<ProductVO>>('/admin/product/page', params)
}

export function getProduct(id: number) {
  return http.get<ProductDetailVO>(`/admin/product/${id}`)
}

export function createProduct(data: ProductSaveDTO) {
  return http.post<number>('/admin/product', data)
}

export function updateProduct(data: ProductSaveDTO) {
  return http.put<void>('/admin/product', data)
}

export function deleteProduct(id: number) {
  return http.delete<void>(`/admin/product/${id}`)
}

/** 上下架。后端把 status 设计成 query 参数而非 body，这里要跟着走 */
export function changeProductStatus(id: number, status: number) {
  return http.put<void>(`/admin/product/${id}/status`, undefined, { params: { status } })
}

export function getCategoryTree() {
  return http.get<CategoryVO[]>('/admin/product/category/tree')
}

/** 分类的增删改（对应后端 ProductCategoryController） */
export interface CategorySaveBody {
  id?: number
  /** 0 表示根分类。后端要求必传，不传会被参数校验拦下 */
  parentId: number
  name: string
  icon?: string
  sort?: number
  status?: number
}

export function createCategory(data: CategorySaveBody) {
  return http.post<number>('/admin/product/category', data)
}

export function updateCategory(data: CategorySaveBody) {
  return http.put<void>('/admin/product/category', data)
}

export function deleteCategory(id: number) {
  return http.delete<void>(`/admin/product/category/${id}`)
}
