/**
 * ============================================================================
 *  端到端模拟：垫片处理真实接口响应后，页面到底能拿到什么
 * ============================================================================
 *  用法：node scripts/simulate-shim-response.js
 *
 *  【为什么要做这个模拟】
 *  "接口返回了数据" 与 "页面能读到数据" 是两件事。
 *  中间隔着垫片的三步处理：资源 URL 补全 → 外层 data 包装 → 分页体解包。
 *  任何一步配置漏了，页面读到的就是 undefined，而接口侧完全正常。
 *
 *  这个脚本直接从 utils/request.js 里抽出这三步的逻辑，
 *  对真实接口响应跑一遍，打印**页面最终会读到的值**。
 *  比"看接口返回 200 就以为对了"可靠得多。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const BASE = process.env.NQX_BASE || 'http://127.0.0.1:8080';
const REQUEST_JS = path.join(__dirname, '..', 'nqx-miniprogram', 'utils', 'request.js');
const src = fs.readFileSync(REQUEST_JS, 'utf8');

// ---------- 从 request.js 里抽出三张配置表 ----------
function stringsOf(name) {
  const re = new RegExp('const\\s+' + name + '\\s*=\\s*new Set\\(\\[([\\s\\S]*?)\\]\\)');
  const m = src.match(re);
  if (m) return (m[1].match(/'([^']+)'/g) || []).map(s => s.slice(1, -1));
  const re2 = new RegExp('const\\s+' + name + '\\s*=\\s*\\[([\\s\\S]*?)\\];');
  const m2 = src.match(re2);
  return m2 ? (m2[1].match(/'([^']+)'/g) || []).map(s => s.slice(1, -1)) : [];
}

const WRAPPED_PATHS = stringsOf('DATA_WRAPPED_PATHS');
const WRAPPED_PREFIXES = stringsOf('DATA_WRAPPED_PREFIXES');
const NOT_WRAPPED = stringsOf('DATA_NOT_WRAPPED');

function isDataWrapped(p) {
  if (p === '/api/quiz/daily') return true; // 已知例外，见 audit-shim-wrapping.js
  if (NOT_WRAPPED.indexOf(p) >= 0) return false;
  if (WRAPPED_PATHS.indexOf(p) >= 0) return true;
  return WRAPPED_PREFIXES.some(x => p.indexOf(x) === 0);
}

function unwrapPagePayload(payload) {
  if (payload && typeof payload === 'object' && Array.isArray(payload.records)) {
    return payload.records;
  }
  return payload;
}

function rewriteAssetUrls(value, depth) {
  if (depth === undefined) depth = 0;
  if (depth > 8 || value === null || value === undefined) return value;
  if (typeof value === 'string') {
    return value.indexOf('/files/') === 0 ? BASE.replace(/\/+$/, '') + value : value;
  }
  if (Array.isArray(value)) {
    const arr = new Array(value.length);
    for (let i = 0; i < value.length; i++) arr[i] = rewriteAssetUrls(value[i], depth + 1);
    return arr;
  }
  if (typeof value === 'object') {
    const out = {};
    const keys = Object.keys(value);
    for (let i = 0; i < keys.length; i++) out[keys[i]] = rewriteAssetUrls(value[keys[i]], depth + 1);
    return out;
  }
  return value;
}

/** 完整复现垫片的成功回调 */
function simulate(legacyPath, resultBody) {
  const payload = resultBody.data;                       // request() 解包 Result.data
  const withAssets = rewriteAssetUrls(payload);
  const body = isDataWrapped(legacyPath)
    ? { data: unwrapPagePayload(withAssets) }
    : withAssets;
  return body;                                           // 即页面里的 res.data
}

// ---------- 场景 ----------
const CASES = [
  { page: '地址列表（回收端）', legacy: '/api/address/list',            api: '/app/address/list',                 read: b => b.data,        desc: '页面读 res.data.data' },
  { page: '地址列表（采购端）', legacy: '/api/procurement/address/list', api: '/app/address/list',                 read: b => b.data,        desc: '页面读 res.data.data ← 之前坏在这里' },
  { page: '商城主页',           legacy: '/api/procurement/products',     api: '/app/product/page?pageSize=2',      read: b => b.data,        desc: '页面读 res.data.data' },
  { page: '积分商城',           legacy: '/api/points/goods',             api: '/app/points/goods?pageSize=2',      read: b => b.data,        desc: '页面读 res.data.data' },
  { page: '积分流水',           legacy: '/api/points/history',           api: '/app/points/record/page?pageSize=2',read: b => b.data,        desc: '页面读 res.data.data' },
  { page: '每日问答',           legacy: '/api/quiz/daily',               api: '/app/question/today',               read: b => b,             desc: '页面读 res.data.hasAnswered 与 res.data.data' }
];

(async () => {
  console.log('='.repeat(78));
  console.log('  垫片端到端模拟：页面最终读到的值');
  console.log('='.repeat(78) + '\n');

  const login = await (await fetch(BASE + '/app/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'shim-sim', role: 'lab' })
  })).json();
  const token = login.data.token;
  console.log(`已登录 userId=${login.data.userInfo.id}\n`);

  let bad = 0;
  for (const c of CASES) {
    const res = await (await fetch(BASE + c.api, { headers: { Authorization: 'Bearer ' + token } })).json();
    const body = simulate(c.legacy, res);
    let value;
    try { value = c.read(body); } catch (e) { value = undefined; }

    const isArr = Array.isArray(value);
    const ok = value !== undefined && value !== null;
    if (!ok) bad++;

    console.log(`${ok ? '✅' : '❌'} ${c.page}`);
    console.log(`     旧路径: ${c.legacy}`);
    console.log(`     实际接口: ${c.api}`);
    console.log(`     垫片包装: ${isDataWrapped(c.legacy) ? '是' : '否'}`);
    if (isArr) {
      console.log(`     页面拿到: 数组，共 ${value.length} 条`);
      if (value.length > 0) console.log(`     首条字段: ${Object.keys(value[0]).join(', ')}`);
    } else if (value && typeof value === 'object') {
      console.log(`     页面拿到: 对象，字段 ${Object.keys(value).join(', ')}`);
    } else {
      console.log(`     ⚠️  页面拿到: ${value}   <-- 这就是"页面空白"的原因`);
    }
    console.log('');
  }

  console.log('='.repeat(78));
  console.log(bad === 0 ? '  ✅ 全部页面都能读到数据' : `  ❌ 有 ${bad} 个页面读不到数据`);
  console.log('='.repeat(78));
})().catch(e => { console.error('模拟失败：', e); process.exit(1); });
