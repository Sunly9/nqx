#!/usr/bin/env node
/**
 * ============================================================================
 *  菜单图标校验
 * ============================================================================
 *
 *  【为什么需要这个脚本】
 *
 *  管理后台的侧边栏图标来自 `sys_permission.icon` 这一列 —— 一个**字符串**。
 *  前端用 `<el-icon><component :is="icon" /></el-icon>` 渲染，
 *  而 Vue 对"不存在的组件名" **不报错**，只是渲染成空。
 *
 *  于是出现过一个真实问题：「回收品类」那一行没有图标，
 *  原因是库里写的是 `Recycle`，而 Element Plus 的 293 个图标里**没有**这个名字。
 *  表现只是"文字前面空了一块"，编译通过、页面不报错、控制台也干净。
 *
 *  同类问题还有小程序里的 `nqx-icon name="layout-grid"`（那个图标不存在），
 *  也是静默渲染成空白。**凡是"字符串指向一个资源名"，都必须校验。**
 *
 *  【怎么用】
 *
 *      node scripts/audit-menu-icons.js
 *      node scripts/audit-menu-icons.js --mysql "D:\mysql\bin\mysql.exe"
 *      node scripts/audit-menu-icons.js --password 123456 --database nqx
 *
 *  退出码：0 = 全部有效；1 = 存在无效图标（可接进 CI）。
 * ============================================================================
 */

const { execFileSync } = require('node:child_process')
const path = require('node:path')
const fs = require('node:fs')

/* ------------------------------ 参数 ------------------------------ */

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1]
  return fallback
}

const MYSQL = arg('mysql', 'D:\\mysql\\install\\bin\\mysql.exe')
const DB = arg('database', 'nqx')
const USER = arg('user', 'root')
const PASSWORD = arg('password', '123456')

const ROOT = path.resolve(__dirname, '..')

/* ------------------------------ 取出 Element Plus 的图标清单 ------------------------------ */

/**
 * 从已安装的 @element-plus/icons-vue 里读出所有图标名。
 *
 * 直接 require 包本身，而不是去解析它的 .d.ts / .js 源码 ——
 * 解析源码我试过一次，正则匹配不到导出名（打包产物里的写法不统一），
 * 而 require 拿到的是真正的导出对象，最可靠。
 */
function loadIconNames() {
  const pkgDir = path.join(ROOT, 'nqx-ui', 'node_modules', '@element-plus', 'icons-vue')
  if (!fs.existsSync(pkgDir)) {
    console.error(`[FAIL] 找不到 @element-plus/icons-vue：${pkgDir}`)
    console.error('       先在 nqx-ui 目录执行 npm install')
    process.exit(1)
  }
  const mod = require(pkgDir)
  return new Set(Object.keys(mod))
}

/* ------------------------------ 查询数据库里的图标 ------------------------------ */

function queryIcons() {
  if (!fs.existsSync(MYSQL)) {
    console.error(`[FAIL] 找不到 mysql 客户端：${MYSQL}`)
    console.error('       用 --mysql 指定路径，例如：')
    console.error('       node scripts/audit-menu-icons.js --mysql "C:\\mysql\\bin\\mysql.exe"')
    process.exit(1)
  }

  const sql =
    "SELECT CONCAT(id, '\t', perm_name, '\t', COALESCE(icon, '')) " +
    'FROM sys_permission ' +
    'WHERE deleted = 0 AND perm_type IN (1, 2) ' +
    'ORDER BY parent_id, sort;'

  let out
  try {
    out = execFileSync(
      MYSQL,
      [
        `-u${USER}`,
        `-p${PASSWORD}`,
        '--batch',
        '--skip-column-names',
        /*
         * ⚠️ --raw 必须加。
         *
         * `mysql --batch` 默认会把结果里的特殊字符**转义后再输出**：
         * 真实的 Tab 会变成字面的两个字符 `\t`，换行变成 `\n`。
         * 不加 --raw 时，下面 split('\t') 完全切不开，
         * 每一行的 `id / name / icon` 会挤成一个字段 ——
         * 表现为"所有菜单都显示没有图标"，而后台明明都配了。
         * 加 --raw 后 Tab 才是真正的 Tab。
         */
        '--raw',
        '--default-character-set=utf8mb4',
        DB,
        '-e',
        sql
      ],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
    )
  } catch (e) {
    console.error('[FAIL] 查询数据库失败，检查 MySQL 是否启动、账号密码是否正确')
    process.exit(1)
  }

  return out
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => {
      const [id, name, icon] = line.split('\t')
      return { id: id.trim(), name: (name || '').trim(), icon: (icon || '').trim() }
    })
}

/* ------------------------------ 主流程 ------------------------------ */

const valid = loadIconNames()
const menus = queryIcons()

console.log('='.repeat(64))
console.log('  菜单图标校验')
console.log('='.repeat(64))
console.log(`  Element Plus 图标总数：${valid.size}`)
console.log(`  菜单节点数：${menus.length}`)
console.log('')

const invalid = []
const missing = []

for (const m of menus) {
  if (!m.icon) {
    // 没有图标不算错：目录节点常常不配图标，子菜单也可以只靠文字
    missing.push(m)
    continue
  }
  if (!valid.has(m.icon)) {
    invalid.push(m)
  }
}

if (missing.length) {
  console.log(`  [提示] ${missing.length} 个菜单没有配图标（不算错误）：`)
  for (const m of missing) {
    console.log(`         id=${m.id}  ${m.name}`)
  }
  console.log('')
}

if (invalid.length === 0) {
  console.log('  [OK] 所有已配置的图标名在 Element Plus 里都存在')
  console.log('')
  process.exit(0)
}

console.log(`  [FAIL] ${invalid.length} 个图标名不存在，这些位置会渲染成空白：`)
for (const m of invalid) {
  console.log(`         id=${m.id}  ${m.name}  ->  "${m.icon}"（Element Plus 无此图标）`)
}
console.log('')
console.log('  修复方式：')
console.log('    1. 从下面的候选里挑一个，写进新的 Flyway 迁移：')
console.log("         UPDATE sys_permission SET icon = 'PriceTag' WHERE id = <节点id>;")
console.log('    2. 或者直接在「系统管理 → 权限菜单」里编辑该节点的图标字段')
console.log('')
console.log('  常用候选（都确认存在于 Element Plus）：')
console.log('    PriceTag / Collection / Box / Sort / Filter / Odometer')
console.log('    Grid / Shop / Goods / Tickets / Refresh / DataLine / TrendCharts')
console.log('')
process.exit(1)
