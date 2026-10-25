/**
 * ============================================================================
 *  全量图标渲染验证（可视化验收页）
 * ============================================================================
 *  用法：node scripts/render-icon-sheet.js
 *
 *  【为什么要做这个验证】
 *
 *  SVG 塞进 CSS `background-image` 的 data URI，失败起来全是"静默"的：
 *
 *    1. 忘了 encodeURIComponent → 颜色里的 `#` 被当成片段标识符，图标不显示；
 *    2. `url()` 的引号与 SVG 内部引号撞了 → 整个 url 被截断；
 *    3. 路径 d 写错 → 仍然是个"合法 SVG"，但画出来是一团鬼东西或一片空白；
 *    4. `stroke` 没生效 → 只画出 fill=none 的空图形，看起来"什么都没有"。
 *
 *  前三条与第四条的一半可以用断言覆盖，但**第 3 条断言查不出来** ——
 *  一个畸形路径在语法上完全合法。所以除了断言，还必须真的用浏览器
 *  渲染出来、亲眼看一遍。
 *
 *  这里**直接 require 真实的 utils/icons.js**（不复制一份逻辑），
 *  保证"测的就是跑在小程序里的那份"。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');
const os = require('os');

const { ICON_PATHS, iconUrl, COLORS } = require('../nqx-miniprogram/utils/icons');

// 预览产物写到系统临时目录，不落在仓库里。
// 理由：这是一次性调试产物，放进仓库会被误提交，而且每次生成内容都不同，
// 会让 git status 永远脏着。需要看的时候跑一次，路径会打印出来。
const OUT_HTML = path.join(os.tmpdir(), 'nqx-icon-sheet.html');

const names = Object.keys(ICON_PATHS);

console.log('='.repeat(72));
console.log('  图标库全量验证');
console.log('='.repeat(72));
console.log(`\n从 utils/icons.js 读到 ${names.length} 个图标\n`);

// ---------------------------------------------------------------------------
// 逐项自检：把上面四个"静默失败点"里的可断言部分全部断言一遍
// ---------------------------------------------------------------------------
let failed = 0;
const failedNames = [];

function check(name, url, color) {
  // 1. 必须是 data URI
  const isData = url.indexOf('data:image/svg+xml,') === 0;
  // 2. `#` 必须编码成 %23：出现裸 # 说明 CSS 会在此处截断
  const noRawHash = url.indexOf('#') < 0;
  const hashEncoded = url.indexOf('%23') >= 0;
  // 3. SVG 内部的双引号必须编码成 %22：出现裸 " 会与外层引号冲突
  const noRawQuote = url.indexOf('"') < 0;
  // 4. 描边颜色必须在里面（还原后检查）
  const decoded = decodeURIComponent(url.replace('data:image/svg+xml,', ''));
  const hasStroke = decoded.indexOf('stroke="' + color + '"') >= 0;
  const hasStrokeWidth = decoded.indexOf('stroke-width="2"') >= 0;
  // 5. 结构完整：起止标签、viewBox、且中间确实有图形元素
  const wellFormed =
    decoded.indexOf('<svg') === 0 &&
    decoded.slice(-6) === '</svg>' &&
    decoded.indexOf('viewBox="0 0 24 24"') > 0;
  // 6. 必须真的有图形元素，不能是空壳
  const hasShape = /<(path|circle|rect|line|polyline|polygon)\b/.test(decoded);

  const ok = isData && noRawHash && hashEncoded && noRawQuote && hasStroke && hasStrokeWidth && wellFormed && hasShape;

  if (!ok) {
    failed++;
    failedNames.push(name);
  }
  return { ok, url, decoded, flags: { isData, noRawHash, hashEncoded, noRawQuote, hasStroke, hasStrokeWidth, wellFormed, hasShape } };
}

console.log('自检（灰/蓝两种颜色各断言一次）：');
const results = {};
for (const name of names) {
  const gray = check(name, iconUrl(ICON_PATHS[name], COLORS.gray), COLORS.gray);
  const blue = check(name, iconUrl(ICON_PATHS[name], COLORS.primary), COLORS.primary);
  results[name] = { gray, blue };

  const ok = gray.ok && blue.ok;
  const which = ok ? `${gray.url.length} 字符` : JSON.stringify(gray.flags);
  console.log(`  ${ok ? '✅' : '❌'} ${name.padEnd(16)} ${which}`);
}

// ---------------------------------------------------------------------------
// 生成预览页：每个图标 64×64，分"灰"与"蓝"两行并标出名字
//
// 之所以用两行而不是一格两色：同一个图标在两行里**上下对齐**，
// 一眼就能看出"哪一个在某一行是空的" —— 空白格是最容易被忽略的失败形态。
// ---------------------------------------------------------------------------
function row(color) {
  return names
    .map(name => {
      const url = iconUrl(ICON_PATHS[name], color);
      return `<div class="cell">
        <div class="big" style="background-image:url('${url}')"></div>
        <div class="name">${name}</div>
      </div>`;
    })
    .join('');
}

const total = names.length;
const cols = 8;

const html = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>nqx-icon 图标总览</title><style>
  body { margin:0; padding:24px 28px; background:#f5f6f8;
         font-family:-apple-system,"Microsoft YaHei",sans-serif; color:#333; }
  h1 { font-size:20px; margin:0 0 4px; }
  .meta { font-size:13px; color:#888; margin-bottom:20px; }
  h2 { font-size:15px; margin:26px 0 12px; padding-left:10px; border-left:4px solid #1989fa; }
  h2.gray { border-left-color:#8a8f99; }
  h2.blue { border-left-color:#1989fa; }
  .grid { display:grid; grid-template-columns:repeat(${cols}, 1fr); gap:14px;
          background:#fff; padding:20px; border-radius:12px;
          box-shadow:0 2px 10px rgba(0,0,0,.04); }
  .cell { text-align:center; }
  /* 64×64：故意放大到远大于实际使用尺寸（16~48px），
     这样描边是否均匀、端点是否圆润、重心是否居中都能看出来 */
  .big { width:64px; height:64px; margin:0 auto;
         background-repeat:no-repeat; background-position:center; background-size:100% 100%; }
  .name { font-size:11px; color:#999; margin-top:6px; line-height:1.2;
          word-break:break-all; font-family:Consolas,monospace; }
</style></head><body>
  <h1>nqx-icon 图标总览</h1>
  <div class="meta">
    共 ${total} 个图标 · 每个 64×64 放大显示 · 两行分别是实际使用的两种主要颜色 ·
    任意一格空白 = 该图标路径有问题的信号
  </div>

  <h2 class="gray">灰 #8a8f99（次要图标 / 未选中 / 空状态）</h2>
  <div class="grid">${row(COLORS.gray)}</div>

  <h2 class="blue">蓝 #1989fa（主色 / 选中 / 强调）</h2>
  <div class="grid">${row(COLORS.primary)}</div>
</body></html>`;

fs.writeFileSync(OUT_HTML, html, 'utf8');

console.log(`\n预览页已生成：${OUT_HTML}`);
console.log(`（共 ${total} 个图标 × 2 行 = ${total * 2} 个 64×64 图标格子）`);

if (failed === 0) {
  console.log('\n✅ 全部断言通过（每个图标在灰/蓝两种颜色下都生成了合法 data URI）');
} else {
  console.log(`\n❌ 有 ${failed} 项断言失败：${failedNames.join(', ')}`);
}
process.exit(failed === 0 ? 0 : 1);
