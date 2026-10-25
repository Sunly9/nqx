/**
 * 别名覆盖率检查脚本
 * ============================================================================
 * 做两件事：
 *   1. 从 pages/ 下的页面源码里**重新采集**所有 wx.cloud.callContainer 的
 *      旧 path 字面量（包括 `/api/xxx/${id}` 这种模板串），不依赖人工抄写；
 *   2. 走一遍垫片的完整解析链（PATH_ALIAS → PATH_PREFIX_ALIAS → ACTION_ALIAS），
 *      看每条旧路径最终会不会真的被改写。
 *
 * 只做静态解析 + 纯函数调用，不真的发请求：
 * 这里直接安装垫片，把 wx.request 换成记录器，
 * 然后按页面里的调用形状（含 data）调 callContainer，
 * 这样"是否被改写、改写成什么方法、响应要不要包一层 data"三项都能一次测出来。
 *
 * 用法：node scripts/check-alias-coverage.js
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

// ---------------------------------------------------------------- 记录器
const sent = []; // 垫片最终发出的请求：{ url, method, data }

global.wx = {
  getStorageSync: () => '',
  setStorageSync: () => {},
  removeStorageSync: () => {},
  login: () => {},
  showToast: () => {},
  cloud: {},
  request: (opts) => {
    sent.push({ url: opts.url, method: opts.method, data: opts.data });
    // 不回 success 也不回 fail：本脚本只关心垫片发出的请求长什么样
  }
};

const shim = require(path.join(ROOT, 'utils', 'request.js'));
shim.installCloudShim();

/** 发一次（假的）请求，返回垫片最终使用的 url/method/data */
function dryRun(p, method, data) {
  sent.length = 0;
  wx.cloud.callContainer({ path: p, method, data });
  return sent[0] || { url: '(未发出)', method: '(未发出)', data: undefined };
}


// ---------------------------------------------------------------- 1. 采集页面里的旧 path
/** 递归收集 .js 文件 */
function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue;
      walk(full, out);
    } else if (entry.name.endsWith('.js')) {
      out.push(full);
    }
  }
  return out;
}

/**
 * 把源码里的模板串归一化成可比较的形态：
 *   `/api/orders/${id}`   → /api/orders/{id}
 *   `/api/procurement/products/${id}/reviews` → /api/procurement/products/{id}/reviews
 */
function normalize(p) {
  return p.replace(/\$\{[^}]*\}/g, '{id}');
}

/**
 * 页面里实际使用的 HTTP 方法。
 *
 * 为什么不从源码里自动推断：调用点与 `method:` 之间隔着 env/service/header 若干行，
 * 而且很多调用点的 method 写在 data 之后，靠行距猜容易出错 ——
 * 验证脚本本身给出错误结论比不给结论更糟。
 * 所以这里**照抄页面源码**（人工核对过每一处），
 * 与 source 不一致时以 source 为准，脚本只做判定不做猜测。
 */
const PAGE_METHOD = {
  '/api/login': 'POST',
  '/api/procurement/login': 'POST',
  '/api/user/info': 'GET',
  '/api/procurement/user/info': 'GET',
  '/api/user/update': 'POST',
  '/api/procurement/user/update': 'POST',
  '/api/orders/create': 'POST',
  '/api/orders/list': 'GET',
  '/api/orders/settle': 'POST',
  '/api/orders/{id}': 'GET',
  '/api/procurement/products': 'GET',
  '/api/procurement/products/{id}': 'GET',
  '/api/procurement/products/{id}/reviews': 'GET',
  '/api/procurement/orders/list': 'GET',
  '/api/procurement/orders/create': 'POST',
  '/api/procurement/orders/pay': 'POST',
  '/api/procurement/orders/cancel': 'POST',
  '/api/procurement/orders/ship': 'POST',
  '/api/procurement/orders/receive': 'POST',
  '/api/procurement/orders/{id}': 'GET',
  '/api/procurement/reviews/add': 'POST',
  '/api/procurement/repair/list': 'GET',
  '/api/procurement/repair/apply': 'POST',
  '/api/address/list': 'GET',
  '/api/procurement/address/list': 'GET',
  '/api/address/add': 'POST',
  '/api/procurement/address/add': 'POST',
  '/api/address/edit/{id}': 'PUT',
  '/api/procurement/address/edit/{id}': 'PUT',
  '/api/address/delete/{id}': 'DELETE',
  '/api/procurement/address/delete/{id}': 'DELETE',
  '/api/points/goods': 'GET',
  '/api/procurement/goods': 'GET',
  '/api/points/history': 'GET',
  '/api/procurement/points/history': 'GET',
  '/api/points/exchange': 'POST',
  '/api/procurement/points/exchange': 'POST',
  '/api/quiz/daily': 'GET',
  '/api/quiz/submit': 'POST',
  '/api/tips/random': 'GET',
  '/api/faqs': 'GET',
  '/api/procurement/faqs': 'GET',
  '/api/announcements/active': 'GET',
  '/api/config/logo': 'GET',
  // 下面三条在页面里是模板串（用模板串参与拼接，不是单行字面量），
  // 归一化后与这里同名，供 §B 的静态取值使用
  '/api/orders/{id}': 'GET',
  '/api/procurement/orders/{id}': 'GET',
  '/api/address/edit/{id}': 'PUT',
  '/api/procurement/address/edit/{id}': 'PUT',
  '/api/address/delete/{id}': 'DELETE',
  '/api/procurement/address/delete/{id}': 'DELETE'
};

const calls = []; // { file, line, path, method }

const jsFiles = walk(path.join(ROOT, 'pages'), []);

for (const file of jsFiles) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    // 只认单行里的 path: '...' 或 path: `...`
    const m = line.match(/path\s*:\s*(['"`])([^'"`]+)\1/);
    if (!m) return;
    const raw = m[2];
    if (!raw.startsWith('/api/')) return; // 只关心后端接口，忽略 pages/xxx 的页面路径
    const p = normalize(raw);
    calls.push({
      file: path.relative(ROOT, file).replace(/\\/g, '/'),
      line: i + 1,
      path: p,
      method: PAGE_METHOD[p] || '(未登记)',
      raw
    });
  });
}

// 变量形式的 path（如 path: path / path: apiPath / path: deletePath）无法静态取值，
// 单独把源码里出现过的 /api/ 字面量也采集一份，作为补充
const literalPaths = new Set();
for (const file of jsFiles) {
  const src = fs.readFileSync(file, 'utf8');
  const re = /['"`](\/api\/[^'"`$]*)['"`]/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    literalPaths.add(normalize(m[1]));
  }
}

// ---------------------------------------------------------------- 2. 逐一走完整垫片链路
/**
 * 用 dryRun 而不是直接调 resolveAlias：
 * 支付/取消/收货这类接口的改写发生在 ACTION_ALIAS（体参数 → 路径参数）里，
 * 只查 PATH_ALIAS 会把它们误判成「没有映射」。
 * 这里按页面真实的调用形状（method + data）跑一遍垫片，才算数。
 */
const rows = [];
for (const c of calls) {
  // 还原成垫片能识别的请求体：页面里这些接口都把订单标识放在 body 里，
  // 字段名不统一（order_id / id），ACTION_ALIAS 两种都认，这里用 order_id=1 试
  const data = { user_id: 1, order_id: 1 };
  const r = dryRun(c.path, c.method, data);
  const newPath = r.url.replace(/^https?:\/\/[^/]+/, '');
  const transformed = newPath !== c.path || r.method !== c.method;
  // 是否会被垫片包一层 { data: ... }（按原路径判断，与 shim 内部一致）
  rows.push({ ...c, newPath, newMethod: r.method, mapped: transformed });
}

// 补充：变量形式的路径（用字面量集合）
const varPaths = [];
for (const p of literalPaths) {
  if (calls.some((c) => c.path === p)) continue;
  const method = PAGE_METHOD[p] || 'GET';
  const r = dryRun(p, method, {});
  const newPath = r.url.replace(/^https?:\/\/[^/]+/, '');
  varPaths.push({ path: p, method, newPath, newMethod: r.method, mapped: newPath !== p });
}


// ---------------------------------------------------------------- 3. 刻意不映射的路径
const INTENTIONALLY_UNMAPPED = {
  '/api/orders/settle': '旧版小程序端结算发积分且无鉴权（任意刷积分），新版收敛到 POST /admin/order/{orderNo}/confirm-recycle',
  '/api/procurement/orders/ship': '旧版小程序端发货，新版收敛到 POST /admin/order/{orderNo}/deliver（权限码 order:purchase:deliver）'
};

// ---------------------------------------------------------------- 4. 输出
console.log('=== A. 页面里 path: 字面量调用点 ===');
console.log(`共采集到 ${calls.length} 处 callContainer path 调用点，去重后 ${new Set(calls.map((c) => c.path)).size} 条旧路径\n`);

const uniq = new Map();
for (const r of rows) {
  if (!uniq.has(r.path)) uniq.set(r.path, r);
}

/** 旧路径是否会被垫片包一层 { data: ... }（直接调 request.js 导出的判断函数） */
const isWrapped = typeof shim.isDataWrapped === 'function'
  ? shim.isDataWrapped
  : () => '(request.js 未导出 isDataWrapped)';

console.log('旧路径 / 方法'.padEnd(52) + '新路径 / 方法'.padEnd(52) + '映射');
for (const [p, r] of [...uniq.entries()].sort()) {
  const flag = INTENTIONALLY_UNMAPPED[p] ? '刻意不映射'
    : (r.mapped ? '有' : '无 ⚠️');
  const from = `${p} ${r.method}`;
  const to = `${r.newPath} ${r.mapped ? r.newMethod : '(未改写)'}`;
  console.log(from.padEnd(52) + to.padEnd(52) + flag);
}

console.log('\n=== B. 变量形式传入的 path（静态取值；method 照抄页面） ===');
for (const v of varPaths.sort((a, b) => a.path.localeCompare(b.path))) {
  const flag = INTENTIONALLY_UNMAPPED[v.path] ? '刻意不映射' : (v.mapped ? '有' : '无 ⚠️');
  const to = `${v.newPath} ${v.mapped ? v.newMethod : '(未改写)'}`;
  console.log(`${v.path} ${v.method}`.padEnd(52) + to.padEnd(52) + flag);
}

console.log('\n=== C. 刻意不映射的接口在页面中的调用点 ===');
for (const [bad, why] of Object.entries(INTENTIONALLY_UNMAPPED)) {
  const hits = calls.filter((c) => c.path === bad);
  console.log(`\n${bad}`);
  console.log(`  原因: ${why}`);
  if (hits.length === 0) {
    console.log('  页面调用点: 无');
  } else {
    hits.forEach((h) => console.log(`  调用点: ${h.file}:${h.line}（${h.method}）`));
  }
}

console.log('\n=== D. 响应包装（旧接口返回风格是否被还原） ===');
console.log('说明：垫片按【原路径】决定要不要把新版干净的 data 再包一层 {data:...}，');
console.log('      这里把每条旧路径的判断结果列出来，便于核对页面里的 res.data.data 写法。');
const wrapKeys = [...uniq.keys()].sort();
for (const p of wrapKeys) {
  const w = isWrapped(p);
  console.log(`${p.padEnd(52)} ${w}`);
}

console.log('\n=== E. 未映射且非刻意下线的路径 ===');
const suspicious = [...uniq.entries()].filter(([p, r]) => !r.mapped && !INTENTIONALLY_UNMAPPED[p]);
console.log(`数量: ${suspicious.length}`);
suspicious.forEach(([p]) => console.log('  ⚠️ ' + p));

