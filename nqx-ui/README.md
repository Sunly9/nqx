# nqx-ui · 纽启新生管理后台

基于 **Vue 3.4 + TypeScript + Vite 5 + Element Plus + Pinia + Vue Router 4（动态路由）+ ECharts 5** 的管理后台，
对接后端 SpringBoot 服务（默认 `http://127.0.0.1:8080`）。

## 快速开始

```bash
npm install          # 包管理器为 npm（本项目不用 pnpm）
npm run dev          # 开发服务器 http://127.0.0.1:5173
npm run build        # vue-tsc 类型检查 + vite 打包
npm run build:only   # 只打包，跳过类型检查
```

默认演示账号：`admin` / `Admin@123`（登录页已有提示）。

## 目录结构

```
src/
├── api/            接口定义，一个后端 Controller 对应一个文件
├── components/     跨页面复用的通用组件（占位页组件等）
├── directives/     全局自定义指令（v-permission 按钮级权限）
├── layout/         主框架：侧边栏 / 面包屑 / 标签页 / 用户下拉
├── router/         静态路由、守卫、动态路由构建
├── stores/         Pinia：user（登录态+菜单+权限）、app（界面状态）
├── styles/         全局基础样式
├── types/          后端契约对应的 TS 类型（api / system / product / order）
├── utils/          request 封装、登录态存储、格式化工具
└── views/          页面，路径与后端菜单的 component 字段一一对应
```

## 关键设计说明

### 1. 动态路由与「刷新丢失」的处理

后端 `/admin/auth/menus` 下发菜单树，`component` 字段是组件路径（如 `product/list/index`）。
`src/router/dynamic.ts` 用 `import.meta.glob('../views/**/*.vue')` 建立「路径字符串 → 组件」映射，
再交给 `router.addRoute` 注册。

刷新页面会清空内存中的路由表，因此 `src/router/guard.ts` 里的守卫承担了恢复职责：

```
有 token 但 routesLoaded 为 false
  → setupDynamicRoutes() 重新拉菜单 + addRoute
  → return { path: to.fullPath, replace: true } 用原目标再解析一次
```

两个必须注意的点：

- **catch-all 路由不能写成 `redirect: '/404'`**。重定向发生在守卫之前，守卫看到的 `to.path`
  会变成 `/404`，于是「重新导航」的目标被替换成 404 —— 现象就是刷新任意业务页面都进 404。
  本项目用 `component: () => import('@/views/error/404.vue')` 直接渲染，URL 保持原样。
- 重新导航必须用 `to.fullPath`（含 query、hash），只传 `to.path` 会丢掉检索参数。

### 2. 统一返回体与 401

后端 **HTTP 状态码恒为 200**，成功与否只体现在 body 的 `code`。
`src/utils/request.ts` 的响应拦截器先判断 `code === 200`，是则直接返回 `data`；
`code === 401` 时清登录态并跳登录页（带 `redirect` 回跳参数）。

跳转动作通过 `utils/auth.ts` 里注册的回调触发，而不是让 `request.ts` 直接 import router ——
否则会形成 `request → store → api → request` 的循环依赖。401 处理带一次性去重开关，
避免并发请求同时失效时连弹多个提示。

### 3. 金额单位

后端全链路用「分」（整数无浮点误差），前端只在渲染与提交的边界做换算，
统一走 `utils/format.ts` 的 `fenToYuan` / `yuanToFen`。

### 4. 按钮级权限

`v-permission="'product:add'"` 或 `v-permission="['product:add','product:edit']"`（满足其一即可）。
无权限时把元素从 DOM 中移除而不是禁用。`SUPER_ADMIN` 角色直接放行。

> 前端隐藏只是体验优化，真正的安全边界是后端每个接口上的 `@SaCheckPermission`。

## 页面实现状态

| 页面 | 路径 | 状态 |
|---|---|---|
| 登录 | `views/login/index.vue` | ✅ 完整 |
| 数据看板 | `views/dashboard/index.vue` | ⚠️ 图表为**演示数据**（后端统计接口未实现，页面有 el-alert 标注） |
| 商品列表 | `views/product/list/index.vue` | ✅ 完整（检索/分页/增删改/上下架/封面上传） |
| 商品分类 | `views/product/category/index.vue` | ✅ 完整（树形 CRUD） |
| 回收品类 | `views/product/recycle/index.vue` | ✅ 完整（分页 CRUD） |
| 回收订单 | `views/order/recycle/index.vue` | ✅ 完整（检索/审核/驳回/确认回收/详情） |
| 采购订单 | `views/order/purchase/index.vue` | ✅ 完整（检索/发货/详情） |
| 积分账户 | `views/points/account/index.vue` | ⚠️ 仅「调整积分」可用（列表接口未实现） |
| 积分流水 | `views/points/record/index.vue` | 🚧 纯骨架，**不发起请求**（接口未实现） |
| 题库 / 答题记录 | `views/question/**` | 🚧 占位页 |
| 系统管理 ×5 | `views/system/**` | 🚧 占位页 |

占位页统一样式由 `components/PlaceholderPage.vue` 提供。**这些文件必须存在**：
动态路由按后端下发的 `component` 路径匹配组件，缺一个文件那个菜单就会加载失败。

## 已知的后端契约差异（详见交付报告）

1. `POST /admin/order/{orderNo}/confirm-recycle` 的 body 字段是 **`actual_weight` / `audit_remark`**（下划线），
   不是驼峰。实测传 `actualWeight` 会返回「请填写实际重量」。
2. `/admin/auth/info` 实际未返回 `avatar` / `phone` / `email`，前端已按可选字段处理。
3. `AdminOrderVO` 中为 null 的字段会被后端省略（非空序列化），前端类型均标为可选。
4. `POST /admin/points/record/page`、`/admin/system/**`、问答、统计等接口尚未实现。
5. `POST /admin/order/{orderNo}/deliver` 不接收快递单号入参。
