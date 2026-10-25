/** 认证与系统管理相关的类型定义（对应后端 AdminInfoVO / MenuVO / AdminLoginVO） */

/** 当前登录管理员信息（AdminInfoVO） */
export interface AdminInfo {
  id: number
  username: string
  realName?: string
  /**
   * 以下三个字段后端 VO 里声明了，但当前接口实现并未回填，
   * 因此标为可选 —— 若强行声明成必填，前端就得写一堆假判断。
   */
  avatar?: string
  phone?: string
  email?: string
  roles: string[]
  permissions: string[]
}

/** 登录返回体（AdminLoginVO） */
export interface AdminLoginResult {
  token: string
  /** 有效期，单位秒 */
  expiresIn: number
  user: AdminInfo
}

/**
 * 后端下发的菜单/路由节点（MenuVO）。
 *
 * `type` 1=目录 2=菜单。目录没有 component，只有菜单才有。
 */
export interface MenuVO {
  id: number
  parentId: number
  name: string
  permCode?: string
  type: number
  path: string
  /** 组件路径，形如 `product/list/index`（相对 src/views，且不带 .vue） */
  component?: string
  icon?: string
  sort?: number
  children?: MenuVO[] | null
}

/**
 * 侧边栏渲染用的精简节点。
 *
 * 不直接把 MenuVO 丢给侧边栏，是因为侧边栏需要「已经拼好的完整路径」，
 * 而 MenuVO 里的是后端配置路径、且目录与子菜单的拼接规则只应存在于一处。
 */
export interface MenuItem {
  /** 完整路由路径，如 /product/list */
  path: string
  title: string
  icon?: string
  children?: MenuItem[]
}
