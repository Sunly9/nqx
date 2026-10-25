/**
 * ============================================================================
 *  接口覆盖度审计：后端实现了哪些、前端真的调了哪些
 * ============================================================================
 *  用法：node scripts/audit-api-coverage.js
 *
 *  【要回答的问题】
 *
 *  1. **后端有、但没任何前端调用** → 要么是给未来留的，要么是"写了没用上"
 *     （本项目里"回收单单独结算"就属于这类：后端有，管理端没接按钮）
 *  2. **前端调了、但后端没有** → 一定是 bug（404），而且往往只在点某个按钮时才暴露
 *  3. **管理端菜单有、但对应接口没有** → 点进去就是报错
 *
 *  这三类问题靠人肉对照 100 多个接口是不现实的，所以机械地做差集。
 *  这与 `check-permissions.ps1`（权限码差集）、
 *  `audit-miniprogram-contract.js`（字段差集）是同一套方法论：
 *  **凡是"字符串键要在两处对齐"的场景，都用差集来找问题。**
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const CONTROLLER_DIR = path.join(ROOT, 'nqx-web', 'src', 'main', 'java', 'com', 'nqx', 'controller');
const UI_API_DIR = path.join(ROOT, 'nqx-ui', 'src', 'api');
const MINI_DIR = path.join(ROOT, 'nqx-miniprogram');

// ---------------------------------------------------------------------------
// 1. 扫后端：从 @RequestMapping + 方法注解拼出完整路径
// ---------------------------------------------------------------------------
function collectBackend() {
  const endpoints = [];
  const files = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const f = path.join(d, e.name);
      if (e.isDirectory()) walk(f);
      else if (e.name.endsWith('.java')) files.push(f);
    }
  })(CONTROLLER_DIR);

  for (const f of files) {
    const src = fs.readFileSync(f, 'utf8');
    const classMapping = (src.match(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"/) || [])[1] || '';

    /*
     * 匹配方法级映射。必须同时支持两种写法：
     *
     *   @PostMapping                       ← 没有括号（很常见，路径全在类上）
     *   @PostMapping("/{id}/status")       ← 有括号
     *   @GetMapping(value = "/page")       ← 有 value 关键字
     *
     * 第一版的正则要求必须有 `(...)`，于是**裸写的 `@PostMapping` 全部漏掉**，
     * 审计结果把"新增/修改商品、角色、权限"这些接口误报成"后端没有"。
     * 一个漏匹配就会产出一整批假警报 —— 所以审计脚本本身也要验证。
     */
    const re = /@(Get|Post|Put|Delete)Mapping(?:\s*\(\s*(?:value\s*=\s*)?"?([^")]*)"?\s*\))?/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      const method = m[1].toUpperCase();
      const sub = (m[2] || '').trim();
      if (!sub && !classMapping) continue;
      let full = classMapping + (sub ? (sub.startsWith('/') ? sub : '/' + sub) : '');
      full = full.replace(/\/+/g, '/').replace(/\/$/, '') || '/';
      endpoints.push({ method, path: full, file: path.basename(f) });
    }
  }
  return endpoints;
}

// ---------------------------------------------------------------------------
// 2. 扫管理后台：api/*.ts 里的 http.xxx('...') 调用
// ---------------------------------------------------------------------------
function collectUiCalls() {
  const calls = [];
  const files = fs.readdirSync(UI_API_DIR).filter(f => f.endsWith('.ts'));
  for (const f of files) {
    const src = fs.readFileSync(path.join(UI_API_DIR, f), 'utf8');
    // 匹配 http.get<...>('url' 或 http.get('url'
    //
    // ⚠️ 泛型里可能**嵌套尖括号**：http.get<PageResult<AdminOrderVO>>('/admin/order/page')
    // 第一版写成 `<[^>]*>`，遇到嵌套就匹配失败，于是所有返回分页的接口
    // （订单列表、商品列表、用户列表……）都被误报成"没被调用"。
    // 用 `[^(]*` 而不是 `[^>]*`，让它一路吃到左括号为止。
    const re = /http\.(get|post|put|delete)\s*(?:<[^(]*>)?\s*\(\s*[`'"]([^`'"]+)[`'"]/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      // 把模板变量归一化成 :id
      let p = m[2].replace(/\$\{[^}]*\}/g, ':p').replace(/\/+$/, '');
      calls.push({ method: m[1].toUpperCase(), path: p, file: f });
    }
  }
  return calls;
}

// ---------------------------------------------------------------------------
// 3. 扫小程序：pages/**/*.js 里的 path: '/api/...'
// ---------------------------------------------------------------------------
function collectMiniCalls() {
  const calls = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const f = path.join(d, e.name);
      if (e.isDirectory()) walk(f);
      else if (e.name.endsWith('.js')) {
        const src = fs.readFileSync(f, 'utf8');
        for (const m of src.matchAll(/path:\s*['"`]([^'"`]+)['"`]/g)) {
          if (!m[1].startsWith('/api/')) continue;
          calls.push({ path: m[1].replace(/\$\{[^}]*\}/g, ':p'), file: path.relative(MINI_DIR, f) });
        }
      }
    }
  })(path.join(MINI_DIR, 'pages'));
  return calls;
}

/**
 * 路径归一化，便于比较。
 *
 * ⚠️ 这里必须同时处理两种占位符写法，否则会产生大量假阳性：
 *
 *   后端 Spring 写法：  /admin/order/{orderNo}/audit
 *   前端 TS 模板写法：  /admin/order/${orderNo}/audit  → 归一化后 /admin/order/:p/audit
 *
 * 第一版只把数字换成 :p、**忘了把 {xxx} 也换掉**，于是两边的同一个接口
 * 永远匹配不上 —— 审计结果报了 36 个"后端没有这个接口"，
 * 全部是假的。差集审计本身也可能出 bug，所以**看到异常大的差集时，
 * 先怀疑审计脚本，再怀疑代码**。
 */
function norm(p) {
  return p
    .replace(/\{[^}]+\}/g, ':p')   // Spring 的 {orderNo} → :p
    .replace(/\$\{[^}]*\}/g, ':p') // JS 模板字符串残留
    .replace(/\/\d+/g, '/:p')      // 具体数字 id → :p
    .replace(/\/+/g, '/')
    .replace(/\/+$/, '');
}

const backend = collectBackend();
const uiCalls = collectUiCalls();
const miniCalls = collectMiniCalls();

console.log('='.repeat(76));
console.log('  接口覆盖度审计');
console.log('='.repeat(76));
console.log(`\n后端接口 ${backend.length} 个`);
console.log(`管理后台调用 ${uiCalls.length} 个`);
console.log(`小程序调用 ${miniCalls.length} 个（旧 /api 路径，经垫片映射到 /app）\n`);

// ---------------------------------------------------------------------------
// 4. 管理后台调用 vs 后端
// ---------------------------------------------------------------------------
const adminBackend = new Map();
for (const e of backend) {
  adminBackend.set(e.method + ' ' + norm(e.path), e);
}

console.log('─'.repeat(76));
console.log('  1. 管理后台 → 后端（调了但不存在 = 会 404）');
console.log('─'.repeat(76));

let missingCount = 0;
const uiSeen = new Set();
for (const c of uiCalls) {
  const key = c.method + ' ' + norm(c.path);
  uiSeen.add(key);
  if (!adminBackend.has(key)) {
    // 宽松匹配：路径一致但方法不同也算"存在但方法不符"
    const samePath = [...adminBackend.keys()].filter(k => k.split(' ')[1] === norm(c.path));
    if (samePath.length) {
      console.log(`  🟡 ${c.file}: ${c.method} ${c.path}`);
      console.log(`       后端有同路径但方法不同 → ${samePath.join(', ')}`);
    } else {
      console.log(`  ❌ ${c.file}: ${c.method} ${c.path}  ← 后端没有这个接口`);
      missingCount++;
    }
  }
}
if (missingCount === 0) console.log('  ✅ 管理后台调用的接口全部存在');

// ---------------------------------------------------------------------------
// 5. 后端有但管理后台没调（可能是"写了没用上"）
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(76));
console.log('  2. 后端有、但管理后台没调用（可能是没接按钮）');
console.log('─'.repeat(76));

const unused = [];
for (const e of backend) {
  if (!e.path.startsWith('/admin')) continue;
  const key = e.method + ' ' + norm(e.path);
  if (!uiSeen.has(key)) unused.push(e);
}
if (unused.length === 0) {
  console.log('  ✅ 管理端接口都有前端调用');
} else {
  for (const u of unused) {
    console.log(`  🟡 ${u.method.padEnd(6)} ${u.path.padEnd(48)} (${u.file})`);
  }
}

// ---------------------------------------------------------------------------
// 6. 小程序 /app 接口覆盖
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(76));
console.log('  3. 小程序侧');
console.log('─'.repeat(76));

// 小程序走的是 /api/**，由垫片映射到 /app/**，所以这里只统计规模与去重
const miniUniq = new Map();
for (const c of miniCalls) {
  miniUniq.set(norm(c.path), (miniUniq.get(norm(c.path)) || 0) + 1);
}
console.log(`  小程序共调用 ${miniUniq.size} 个不同的 /api 路径`);
const appBackend = backend.filter(e => e.path.startsWith('/app'));
console.log(`  后端 /app 接口 ${appBackend.length} 个`);

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------
console.log('\n' + '='.repeat(76));
if (missingCount === 0 && unused.length === 0) {
  console.log('  ✅ 接口层没有发现缺口');
} else {
  console.log(`  ⚠️ 缺失 ${missingCount} 个；后端有但没接 ${unused.length} 个`);
}
console.log('='.repeat(76));
