/**
 * ============================================================================
 *  小程序页面 ↔ 后端接口「字段契约」批量核对
 * ============================================================================
 *  用法：node scripts/audit-miniprogram-contract.js
 *
 *  【为什么写这个】
 *  迁移过程中最耗时的不是逻辑错误，而是「页面读的字段名后端没下发」——
 *  页面不报错、接口返回 200、数据就是不出来。这类问题一个一个等用户反馈
 *  效率极低，必须用机械方法一次性找全。
 *
 *  【方法】
 *  对每一对（页面模板, 后端接口）：
 *    1. 从 WXML 里提取所有 {{ xxx.yyy }} 形式的字段引用；
 *    2. 真实调用后端接口，提取响应里的字段名；
 *    3. 做差集 —— 页面引用了但响应里没有的，就是会显示空白的字段。
 *
 *  这就是「字符串键需要在两处对齐时，用集合差集找问题」的同一个套路，
 *  与 scripts/check-permissions.ps1 是姊妹工具。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const BASE = process.env.NQX_BASE || 'http://127.0.0.1:8080';
const MP = path.join(__dirname, '..', 'nqx-miniprogram');

// ---------------------------------------------------------------------------
// 身份：mock 模式下不带 dev: 前缀的 code 会固定映射到同一个用户，
// 与小程序里 wx.login() 拿到的身份一致
// ---------------------------------------------------------------------------
let APP_TOKEN = '';
let ADMIN_TOKEN = '';

async function http(method, p, body, token) {
  const headers = { 'Content-Type': 'application/json; charset=utf-8' };
  if (token) headers.Authorization = 'Bearer ' + token;
  const res = await fetch(BASE + p, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  });
  return res.json();
}

const get = (p, t) => http('GET', p, undefined, t);
const post = (p, b, t) => http('POST', p, b, t);

/** 采集响应里出现的全部字段名（递归，最多三层；数组会取第一个元素继续挖） */
function collectFields(value, into = new Set(), depth = 0) {
  if (depth > 3 || value === null || typeof value !== 'object') return into;
  if (Array.isArray(value)) {
    // 空数组挖不出字段，但至少要标注它是个数组
    if (value.length === 0) return into;
    collectFields(value[0], into, depth + 1);
    return into;
  }
  for (const [k, v] of Object.entries(value)) {
    into.add(k);
    if (v && typeof v === 'object') collectFields(v, into, depth + 1);
  }
  return into;
}

/** 从 WXML 提取 {{ a.b }} 形式里 a.b 的根字段与其子字段 */
function collectTemplateRefs(wxmlPath) {
  const refs = new Map(); // 根变量 -> Set(子字段)
  if (!fs.existsSync(wxmlPath)) return refs;
  const text = fs.readFileSync(wxmlPath, 'utf8');
  const matches = text.match(/\{\{[^}]*\}\}/g) || [];
  for (const m of matches) {
    const inner = m.slice(2, -2);
    // 形如 order.address.name / item.products / product.stock
    const idents = inner.match(/\b[a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)+\b/g) || [];
    for (const ident of idents) {
      const parts = ident.split('.');
      const root = parts[0];
      const rest = parts.slice(1).join('.');
      if (!refs.has(root)) refs.set(root, new Set());
      refs.get(root).add(rest);
    }
  }
  return refs;
}

// ---------------------------------------------------------------------------
// 待核对的（页面, 模板文件, 取数据的接口, 模板里代表"一条记录"的变量名）
// ---------------------------------------------------------------------------
const CASES = [
  { name: '商城主页',        wxml: 'pages/procurement/home/index.wxml',            api: '/app/product/page?pageSize=1',        itemVar: 'item' },
  { name: '商品详情',        wxml: 'pages/procurement/product/detail/index.wxml',  api: null,                                  itemVar: 'product' },
  { name: '地址列表',        wxml: 'pages/address/list/index.wxml',                api: '/app/address/list',                   itemVar: 'item' },
  { name: '采购订单列表',    wxml: 'pages/procurement/orders/index.wxml',          api: '/app/order/page?orderType=2&pageSize=1', itemVar: 'item' },
  { name: '采购订单详情',    wxml: 'pages/procurement/orders/detail/index.wxml',   api: null,                                  itemVar: 'order' },
  { name: '回收订单列表',    wxml: 'pages/order/list/index.wxml',                  api: '/app/order/page?orderType=1&pageSize=1', itemVar: 'item' },
  { name: '回收订单详情',    wxml: 'pages/order/detail/index.wxml',                api: null,                                  itemVar: 'order' },
  { name: '积分商城(商品页)', wxml: 'pages/points/index.wxml',                    api: '/app/points/goods?pageSize=1',        itemVar: 'item' },
  { name: '积分流水',        wxml: 'pages/points/index.wxml',                      api: '/app/points/record/page?pageSize=1',  itemVar: 'item' },
  { name: '每日问答',        wxml: 'pages/lab/quiz/index.wxml',                    api: '/app/question/today',                 itemVar: 'data' },
  { name: '我的地址(选择)',  wxml: 'pages/address/list/index.wxml',                api: '/app/address/list',                   itemVar: 'item' }
];

(async () => {
  console.log('='.repeat(78));
  console.log('  小程序页面 ↔ 后端接口 字段契约核对');
  console.log('='.repeat(78));

  /*
   * 用「有完整数据的用户」来做核对。
   *
   * 这一点很重要：第一次跑时我用了小程序里默认的 mock 身份（一个新用户），
   * 它没有任何订单，于是所有字段都挖不出来，审计把每个订单页都误报成"缺字段"。
   * 用一个数据齐全的用户才能真正暴露契约问题。
   */
  const login = await post('/app/auth/login', { code: 'dev:zhangsan', role: 'lab' });
  APP_TOKEN = login.data && login.data.token;
  const admin = await post('/admin/auth/login', { username: 'admin', password: 'Admin@123' });
  ADMIN_TOKEN = admin.data && admin.data.token;
  if (!APP_TOKEN) { console.error('登录失败', login); process.exit(1); }
  console.log(`\n已登录，用户 id=${login.data.userInfo.id}  昵称=${login.data.userInfo.nickname}\n`);

  // 取一条真实订单号，供详情类接口使用
  const orderPage = await get('/app/order/page?orderType=2&pageSize=1', APP_TOKEN);
  const firstOrderNo = orderPage.data && orderPage.data.records && orderPage.data.records[0]
    ? orderPage.data.records[0].order_no : null;
  const recyclePageForAudit = await get('/app/order/page?orderType=1&pageSize=1', APP_TOKEN);
  const firstRecycleNo = recyclePageForAudit.data && recyclePageForAudit.data.records
    && recyclePageForAudit.data.records[0] ? recyclePageForAudit.data.records[0].order_no : null;
  const productPage = await get('/app/product/page?pageSize=1', APP_TOKEN);
  const firstProductId = productPage.data && productPage.data.records && productPage.data.records[0]
    ? productPage.data.records[0].id : 1;

  const resolveApi = (c) => {
    if (c.api) return c.api;
    if (c.name.includes('商品详情')) return `/app/product/${firstProductId}`;
    if (c.name.includes('采购订单详情')) return firstOrderNo ? `/app/order/${firstOrderNo}` : null;
    if (c.name.includes('回收订单详情')) return firstRecycleNo ? `/app/order/${firstRecycleNo}` : null;
    return null;
  };

  let problemPages = 0;

  for (const c of CASES) {
    const apiPath = await resolveApi(c);
    if (!apiPath) { console.log(`【${c.name}】跳过（无可用数据）\n`); continue; }

    const resp = await get(apiPath, APP_TOKEN);
    const payload = resp.data;
    const apiFields = collectFields(payload);

    const refs = collectTemplateRefs(path.join(MP, c.wxml));
    const itemRefs = refs.get(c.itemVar) || new Set();

    // 差集：模板引用了、但接口响应里没有的字段
    const missing = [...itemRefs].filter((f) => {
      const root = f.split('.')[0];
      // 模板里常见的前端自造字段（在 JS 里赋值）不算接口缺失
      const frontEndComputed = ['formattedDate', 'statusText', 'statusDesc', 'typeText', 'displayAddress'];
      if (frontEndComputed.includes(f)) return false;
      return !apiFields.has(root);
    });

    const status = missing.length === 0 ? '✅' : '❌';
    if (missing.length > 0) problemPages++;
    console.log(`${status} 【${c.name}】  ${apiPath}`);
    console.log(`     接口返回字段 (${apiFields.size}): ${[...apiFields].slice(0, 16).join(', ')}${apiFields.size > 16 ? ' ...' : ''}`);
    console.log(`     模板引用字段 (${itemRefs.size}): ${[...itemRefs].slice(0, 16).join(', ')}${itemRefs.size > 16 ? ' ...' : ''}`);
    if (missing.length > 0) {
      console.log(`     ⚠️  模板引用了但接口未下发: ${missing.join(', ')}`);
    }
    console.log('');
  }

  console.log('='.repeat(78));
  console.log(`  有问题的页面数：${problemPages}`);
  console.log('='.repeat(78));
})().catch((e) => { console.error('核对失败：', e); process.exit(1); });
