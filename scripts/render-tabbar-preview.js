/**
 * ============================================================================
 *  tabBar 图标渲染验证
 * ============================================================================
 *  用法：node scripts/render-tabbar-preview.js
 *
 *  【为什么要做这个验证】
 *
 *  SVG 放在 CSS `background-image` 的 data URI 里，有三个很容易静默失败的环节：
 *
 *    1. 忘了 `encodeURIComponent` → 颜色里的 `#` 被当成片段标识符，图标不显示；
 *    2. `url()` 的引号与 SVG 内部的引号撞了 → 整个 url 被截断；
 *    3. `stroke` 没生效 → 只画出 fill=none 的空图形，看起来"什么都没有"。
 *
 *  这三种都不会报错 —— 页面只是"图标没了"，而这是最难查的那类问题。
 *  所以这里**直接加载真实的 index.js**（不复制一份逻辑，避免测试与实现脱节），
 *  把生成的 data URI 塞进真实浏览器渲染出来，用眼睛确认。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', 'nqx-miniprogram', 'custom-tab-bar', 'index.js');

/*
 * 预览产物写到系统临时目录，不落在仓库里。
 *
 * 理由：这是"看一眼确认图标没画歪"的调试产物，属于一次性文件。
 * 放在仓库根目录会被误提交（而且每次生成的 hash 都不同，
 * 会让 git status 永远脏着）。需要看的时候跑一次脚本，路径会打印出来。
 */
const OUT_HTML = path.join(require('os').tmpdir(), 'nqx-tabbar-preview.html');

// ---------------------------------------------------------------------------
// 从真实的 index.js 里取出图标定义与 iconUrl 函数
//
// 截取 `Component({` 之前的部分（const 定义 + iconUrl 函数）在 Node 里求值，
// 这样测的就是真正跑在小程序里的那份代码。
//
// ⚠️ 这段前导代码现在含 `require('../utils/icons')`（图标定义已抽到公共模块），
// 而 `new Function` 创造的作用域里**没有 CommonJS 的 require**，
// 所以必须把它显式作为参数传进去，并且用与 custom-tab-bar/index.js 相同的
// 相对基准目录去解析 —— 否则会去找 scripts/../utils/icons 而报模块不存在。
// ---------------------------------------------------------------------------
const source = fs.readFileSync(SRC, 'utf8');
const cut = source.indexOf('Component({');
if (cut < 0) {
  console.error('❌ 在 index.js 里找不到 Component({');
  process.exit(1);
}
const preamble = source.slice(0, cut);

// 以 custom-tab-bar 目录为基准的 require：
// 既支持 './xxx' 也支持 '../xxx'，与小程序里的相对路径语义一致
const BASE_DIR = path.dirname(SRC);
const sandboxRequire = request =>
  require(request.startsWith('.') ? path.resolve(BASE_DIR, request) : request);

// eslint-disable-next-line no-new-func
const sandbox = new Function(
  'require',
  preamble +
    '\nreturn { ICON_PATHS, iconUrl, COLOR_INACTIVE, COLOR_ACTIVE_LAB, COLOR_ACTIVE_PROCUREMENT };'
)(sandboxRequire);
const { ICON_PATHS, iconUrl, COLOR_INACTIVE, COLOR_ACTIVE_LAB, COLOR_ACTIVE_PROCUREMENT } = sandbox;

console.log('='.repeat(70));
console.log('  tabBar 图标验证');
console.log('='.repeat(70));
console.log(`\n从 index.js 读到 ${Object.keys(ICON_PATHS).length} 个图标：${Object.keys(ICON_PATHS).join(', ')}\n`);

// ---------------------------------------------------------------------------
// 逐项自检：把三个"容易静默失败"的点都断言一遍
// ---------------------------------------------------------------------------
let failed = 0;
const check = (name, ok, detail) => {
  console.log(`  ${ok ? '✅' : '❌'} ${name}${detail ? '  → ' + detail : ''}`);
  if (!ok) failed++;
};

console.log('自检：');
for (const name of Object.keys(ICON_PATHS)) {
  const url = iconUrl(ICON_PATHS[name], COLOR_ACTIVE_PROCUREMENT);

  // 1. 必须是 data URI
  const isData = url.startsWith('data:image/svg+xml,');
  // 2. `#` 必须被编码成 %23（否则 CSS 会在 # 处截断）
  const hashEncoded = url.indexOf('#') < 0 && url.indexOf('%23') >= 0;
  // 3. SVG 内部的双引号必须被编码成 %22（否则与外层单引号混用会出问题）
  const quoteEncoded = url.indexOf('"') < 0;
  // 4. 描边颜色必须在里面（用 decodeURIComponent 还原后检查）
  const decoded = decodeURIComponent(url.replace('data:image/svg+xml,', ''));
  const hasStroke = decoded.indexOf(`stroke="${COLOR_ACTIVE_PROCUREMENT}"`) >= 0;
  // 5. 必须是合法的 SVG 片段
  const wellFormed = decoded.startsWith('<svg') && decoded.endsWith('</svg>') && decoded.indexOf('viewBox="0 0 24 24"') > 0;

  const ok = isData && hashEncoded && quoteEncoded && hasStroke && wellFormed;
  check(
    name.padEnd(9),
    ok,
    ok ? `${url.length} 字符` : `data=${isData} #编码=${hashEncoded} 引号编码=${quoteEncoded} 描边=${hasStroke} 结构=${wellFormed}`
  );
}

// ---------------------------------------------------------------------------
// 生成预览页：包含"真实尺寸的 tabBar"与"放大 4 倍的图标明细"
// ---------------------------------------------------------------------------
const roles = [
  { title: '回收端（绿色主题）', active: COLOR_ACTIVE_LAB, tabs: [['recycle', '回收'], ['order', '订单'], ['user', '我的']] },
  { title: '采购端（蓝色主题，带购物车角标）', active: COLOR_ACTIVE_PROCUREMENT, tabs: [['mall', '商城'], ['cart', '购物车'], ['user', '我的']] }
];

function tabBarHtml(role) {
  const items = role.tabs.map(([icon, text], i) => {
    const selected = i === 0;
    const url = iconUrl(ICON_PATHS[icon], selected ? role.active : COLOR_INACTIVE);
    const badge = icon === 'cart' ? '<span class="badge">3</span>' : '';
    return `<div class="item ${selected ? 'active' : ''}">
      <div class="icon-wrap"><div class="icon" style="background-image:url('${url}')"></div>${badge}</div>
      <div class="label" style="color:${selected ? role.active : COLOR_INACTIVE}">${text}</div>
    </div>`;
  }).join('');
  return `<div class="phone"><div class="tabbar">${items}</div></div>`;
}

const allIcons = Object.keys(ICON_PATHS).map(name => {
  const off = iconUrl(ICON_PATHS[name], COLOR_INACTIVE);
  const on = iconUrl(ICON_PATHS[name], COLOR_ACTIVE_PROCUREMENT);
  return `<div class="cell">
    <div class="big" style="background-image:url('${off}')"></div>
    <div class="big" style="background-image:url('${on}')"></div>
    <div class="name">${name}</div>
  </div>`;
}).join('');

const html = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
  body { margin:0; padding:24px; background:#f5f6f8; font-family:-apple-system,"Microsoft YaHei",sans-serif; }
  h2 { font-size:15px; color:#333; margin:22px 0 8px; }
  .row { display:flex; gap:20px; align-items:flex-end; }
  /* 真实尺寸的手机容器：宽度按 iPhone 375pt 缩成 375px 便于观察 */
  .phone { width:375px; background:#fff; border-radius:10px 10px 0 0; box-shadow:0 2px 12px rgba(0,0,0,.08); overflow:hidden; }
  .tabbar { display:flex; height:50px; border-top:1px solid rgba(0,0,0,.06); }
  .item { flex:1; height:50px; display:flex; flex-direction:column; align-items:center; justify-content:center; position:relative; }
  .icon-wrap { position:relative; width:22px; height:22px; }
  .icon { width:100%; height:100%; background-repeat:no-repeat; background-position:center; background-size:100% 100%; }
  .item.active .icon { transform:translateY(-1px) scale(1.06); }
  .label { font-size:10px; line-height:1; margin-top:3px; }
  .item.active .label { font-weight:600; }
  .badge { position:absolute; top:-4px; left:100%; margin-left:-6px; min-width:16px; height:16px; padding:0 4px;
           box-sizing:border-box; border-radius:8px; background:#ff4d4f; color:#fff; font-size:10px;
           line-height:16px; text-align:center; border:1px solid #fff; white-space:nowrap; }
  /* 放大区 */
  .grid { display:flex; gap:10px; flex-wrap:wrap; background:#fff; padding:14px; border-radius:10px; }
  .cell { text-align:center; }
  .big { width:64px; height:64px; background-repeat:no-repeat; background-position:center; background-size:100% 100%; }
  .name { font-size:11px; color:#999; margin-top:4px; }
  .legend { font-size:12px; color:#888; margin:6px 0; }
</style></head><body>
  <h2>真实尺寸（375px 宽的手机，50px 高 tabBar）</h2>
  <div class="row">${roles.map(tabBarHtml).join('')}</div>
  <div class="legend">左：未选中（灰 #8a8f99）　右：选中（主题色）　购物车上的红点是角标</div>

  <h2>放大 4 倍看细节（左：未选中　右：选中）</h2>
  <div class="grid">${allIcons}</div>
</body></html>`;

fs.writeFileSync(OUT_HTML, html, 'utf8');
console.log(`\n预览页已生成：${OUT_HTML}`);
console.log(failed === 0 ? '\n✅ 全部自检通过' : `\n❌ 有 ${failed} 项自检失败`);
process.exit(failed === 0 ? 0 : 1);
