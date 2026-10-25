/** 商品与分类相关类型定义 */

/** 商品列表项（ProductVO）。注意价格单位是「分」 */
export interface ProductVO {
  id: number
  categoryId: number
  categoryName?: string
  name: string
  subTitle?: string
  spec?: string
  /** 封面图。后端开启非空序列化，为 null 时该字段直接不出现，故必须可选 */
  coverUrl?: string
  /**
   * 商品详情（长文本）。
   *
   * 必须出现在**列表**类型里，因为管理端的编辑弹窗是用列表行回填的。
   * 列表不返回它时，弹窗只能给空字符串，一保存就会把原描述清空 ——
   * 这是真实发生过的数据丢失（已修复，见后端 ProductVO 的注释）。
   */
  description?: string
  /** 多图（原始 JSON 数组字符串）。字段名 imagesRaw 是为了与详情 VO 的 images 数组区分 */
  imagesRaw?: string
  /** 1采购商品 2积分兑换商品 3两者皆可 */
  productType: number
  /** 单价，单位：分 */
  price: number
  /** 积分价 */
  pointsPrice?: number
  unit: string
  stock: number
  soldCount?: number
  /** 每人限兑数量，0 表示不限 */
  exchangeLimit?: number
  /** 0否 1是 */
  isHot?: number
  /** 0下架 1上架 */
  status: number
  sort?: number
  createTime?: string
}

/** 商品详情（ProductDetailVO），比列表项多出轮播图与描述 */
export interface ProductDetailVO extends ProductVO {
  images?: string[]
  description?: string
}

/** 商品多条件检索条件 */
export interface ProductQuery {
  pageNum: number
  pageSize: number
  name?: string
  categoryId?: number
  productType?: number
  status?: number
  isHot?: number
  minPrice?: number
  maxPrice?: number
  stockLessThan?: number
}

/** 商品新增/修改入参（ProductSaveDTO）。id 为空即新增 */
export interface ProductSaveDTO {
  id?: number
  categoryId?: number
  name: string
  subTitle?: string
  spec?: string
  coverUrl?: string
  images?: string[]
  description?: string
  productType?: number
  /** 单位：分 */
  price?: number
  pointsPrice?: number
  unit: string
  stock?: number
  exchangeLimit?: number
  isHot?: number
  status?: number
  sort?: number
}

/** 商品分类树节点（CategoryVO） */
export interface CategoryVO {
  id: number
  parentId: number
  name: string
  sort?: number
  status?: number
  children?: CategoryVO[] | null
}

/** 回收品类（RecycleCategoryVO） */
export interface RecycleCategoryVO {
  id: number
  name: string
  unit: string
  /** 每公斤可得积分 */
  pointsPerKg: number
  description?: string
  status: number
  sort?: number
  createTime?: string
}

/** 回收品类检索条件 */
export interface RecycleCategoryQuery {
  pageNum: number
  pageSize: number
  name?: string
  status?: number
}
