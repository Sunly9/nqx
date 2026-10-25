/**
 * ============================================================================
 *  手册一致性检查
 * ============================================================================
 *  用法：node scripts/audit-manual-consistency.js
 *
 *  【为什么需要它】
 *
 *  手册最容易出的问题不是"写错一句话"，而是**和代码悄悄脱节**：
 *
 *    · 页面改名了，手册里的路径还指着旧的 → 读者点进小程序的对应页面找不到东西
 *    · 手册之间互相引用的链接指向不存在的文件 → 点开是 404
 *    · 手册里的相对链接写错了层级 → 同上
 *    · 手册提到了 app.json 里根本不存在的页面 → 那个功能可能根本没做
 *    · 编号文档（如 docs/06）里的"共 N 条"与实际条数不符
 *
 *  这些都不会报错 —— 手册照样渲染，只是内容是错的。
 *  所以用脚本机械地核对，而不是靠人记得去改。
 *
 *  ⚠️ 本脚本只做**结构一致性**检查（文件存在性、路径存在性、链接可达性）。
 *     它无法检查"描述是否符合界面"——那需要人对着截图核对。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MANUAL_DIR = path.join(ROOT, 'docs', 'manual');
const APP_JSON = path.join(ROOT, 'nqx-miniprogram', 'app.json');

let problems = 0;
const fail = (msg) => { console.log(`  ❌ ${msg}`); problems++; };
const ok = (msg) => console.log(`  ✅ ${msg}`);
const warn = (msg) => console.log(`  🟡 ${msg}`);

// ---------------------------------------------------------------------------
// 收集手册文件
// ---------------------------------------------------------------------------
if (!fs.existsSync(MANUAL_DIR)) {
  console.log('手册目录不存在：' + MANUAL_DIR);
  process.exit(1);
}

const manuals = fs.readdirSync(MANUAL_DIR).filter(f => f.endsWith('.md')).sort();
if (manuals.length === 0) {
  console.log('没有找到任何手册文件');
  process.exit(1);
}

console.log('='.repeat(72));
console.log('  手册一致性检查');
console.log('='.repeat(72));
console.log(`\n发现 ${manuals.length} 本手册：\n`);
for (const m of manuals) {
  const size = fs.statSync(path.join(MANUAL_DIR, m)).size;
  console.log(`  ${m.padEnd(28)} ${(size / 1024).toFixed(1)} KB`);
}

// ---------------------------------------------------------------------------
// 1. 手册之间的相对链接是否可达
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(72));
console.log('  1. 手册内部链接');
console.log('─'.repeat(72));

let linkCount = 0;
for (const m of manuals) {
  const text = fs.readFileSync(path.join(MANUAL_DIR, m), 'utf8');
  // 匹配 markdown 链接，排除外链与锚点
  const re = /\[([^\]]*)\]\(([^)]+)\)/g;
  let match;
  while ((match = re.exec(text)) !== null) {
    const target = match[2];
    if (/^(https?:|mailto:|#)/.test(target)) continue;
    linkCount++;
    const clean = target.split('#')[0];
    if (!clean) continue;
    // 手册之间的链接是相对 docs/manual/ 的；指向其他 docs 的用 ../ 开头
    const resolved = path.resolve(MANUAL_DIR, clean);
    if (!fs.existsSync(resolved)) {
      fail(`${m}: 链接 [${match[1]}](${target}) → 文件不存在`);
    }
  }
}
// 顺便检查 README 里指向手册的链接
const mainReadme = path.join(ROOT, 'README.md');
if (fs.existsSync(mainReadme)) {
  const text = fs.readFileSync(mainReadme, 'utf8');
  const re = /\[([^\]]*)\]\((docs\/manual\/[^)#]+)(#[^)]*)?\)/g;
  let match;
  let found = 0;
  while ((match = re.exec(text)) !== null) {
    found++;
    const resolved = path.resolve(ROOT, match[2]);
    if (!fs.existsSync(resolved)) {
      fail(`README.md: 链接 [${match[1]}](${match[2]}) → 文件不存在`);
    }
  }
  if (found > 0) ok(`README.md 指向手册的链接 ${found} 条，全部可达`);
  else warn('README.md 里没有指向手册的链接（手册可能没人发现）');
}
if (problems === 0) ok(`${linkCount} 条手册内部链接全部可达`);

// ---------------------------------------------------------------------------
// 2. 手册提到的页面路径是否都在 app.json 里
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(72));
console.log('  2. 手册提到的小程序页面是否存在');
console.log('─'.repeat(72));

if (!fs.existsSync(APP_JSON)) {
  warn('找不到 app.json，跳过页面检查');
} else {
  // app.json 里可能有注释，去掉尾随逗号风格的问题用宽松解析
  const raw = fs.readFileSync(APP_JSON, 'utf8');
  const declared = new Set(
    [...raw.matchAll(/"(pages\/[A-Za-z0-9_\-/]+)"/g)].map(m => m[1])
  );

  let pageRefs = 0;
  for (const m of manuals) {
    const text = fs.readFileSync(path.join(MANUAL_DIR, m), 'utf8');
    // 手册里出现的 pages/xxx/yyy 形式
    const refs = new Set([...text.matchAll(/pages\/[A-Za-z0-9_\-/]+/g)].map(x => x[0]));
    for (const r of refs) {
      pageRefs++;
      if (!declared.has(r)) {
        fail(`${m}: 提到页面 "${r}" 但 app.json 里没有声明`);
      }
    }
  }
  if (pageRefs === 0) {
    ok('手册里没有引用具体页面路径（可以接受，说明都用界面文案描述）');
  } else {
    ok(`核对 ${pageRefs} 处页面引用，与 app.json 声明一致`);
  }
}

// ---------------------------------------------------------------------------
// 3. 交叉核对：手册里引用的其它 docs 文件
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(72));
console.log('  3. 手册引用的其它文档');
console.log('─'.repeat(72));

let docRefs = 0;
for (const m of manuals) {
  const text = fs.readFileSync(path.join(MANUAL_DIR, m), 'utf8');
  const refs = new Set([...text.matchAll(/(?:\.\.\/)?(\d{2}-[^\s)）"']+\.md)/g)].map(x => x[1]));
  for (const r of refs) {
    docRefs++;
    const candidates = [
      path.join(ROOT, 'docs', r),
      path.join(MANUAL_DIR, r)
    ];
    if (!candidates.some(p => fs.existsSync(p))) {
      fail(`${m}: 引用 "${r}" 但 docs/ 下找不到该文件`);
    }
  }
}
if (docRefs === 0) ok('没有引用其它文档');
else ok(`核对 ${docRefs} 处文档引用，全部存在`);

// ---------------------------------------------------------------------------
// 4. docs/06 的"共 N 条"与实际条数是否一致
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(72));
console.log('  4. 文档自述数量与实际是否相符');
console.log('─'.repeat(72));

const pitfalls = path.join(ROOT, 'docs', '06-踩坑记录.md');
if (fs.existsSync(pitfalls)) {
  const text = fs.readFileSync(pitfalls, 'utf8');
  const actual = (text.match(/^### \d+\./gm) || []).length;

  // README 与 docs/06 里出现"共 N 个坑"这类说法
  const claims = [];
  for (const [label, p] of [['README.md', mainReadme], ['docs/06-踩坑记录.md', pitfalls]]) {
    if (!fs.existsSync(p)) continue;
    const t = fs.readFileSync(p, 'utf8');
    for (const mm of t.matchAll(/\*\*(\d+)\s*个[\u4e00-\u9fa5]*坑/g)) {
      claims.push({ label, claimed: Number(mm[1]) });
    }
  }

  if (claims.length === 0) {
    warn('没有找到"共 N 个坑"这类自述数字，跳过');
  } else {
    for (const c of claims) {
      if (c.claimed === actual) {
        ok(`${c.label}: 自述 ${c.claimed} 条，实际 ${actual} 条`);
      } else {
        fail(`${c.label}: 自述 ${c.claimed} 条，但实际有 ${actual} 条`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 5. 手册结构完整性（是否有必备章节）
// ---------------------------------------------------------------------------
console.log('\n' + '─'.repeat(72));
console.log('  5. 手册结构');
console.log('─'.repeat(72));

/**
 * 去掉围栏代码块（``` ... ```）后再统计标题。
 *
 * ⚠️ 必须先剥离代码块，否则**代码里的注释会被当成 Markdown 标题**：
 * 运维手册里有大量这样的片段
 *
 *     ```powershell
 *     # 构建
 *     & mvn ... package
 *     ```
 *
 * 其中的 `# 构建` 形如一级标题，会让"标题数量"统计虚高，
 * 报出一个根本不存在的结构问题。第一版脚本就踩了这个坑
 * （报"04-部署运维手册 一级标题 12 个"）。
 *
 * 这是"用正则解析 Markdown"的典型陷阱：正则不看上下文，
 * 而代码块内的内容本来就不该参与文档结构分析。
 */
function stripCodeFences(text) {
  return text.replace(/```[\s\S]*?```/g, '');
}

for (const m of manuals) {
  if (m === 'README.md') continue;
  const text = stripCodeFences(fs.readFileSync(path.join(MANUAL_DIR, m), 'utf8'));
  const h1 = (text.match(/^# /gm) || []).length;
  const h2 = (text.match(/^## /gm) || []).length;

  const issues = [];
  if (h1 !== 1) issues.push(`一级标题 ${h1} 个（应为 1）`);
  if (h2 < 3) issues.push(`二级标题只有 ${h2} 个（结构可能太单薄）`);
  if (/\bTODO\b|待补充|此处省略/.test(text)) issues.push('含 TODO/待补充 占位');
  if (/见下图|如下图所示|如下图/.test(text)) issues.push('引用了不存在的配图');

  if (issues.length) fail(`${m}: ${issues.join('；')}`);
  else ok(`${m}: 结构正常（${h2} 个二级章节）`);
}

// ---------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------
console.log('\n' + '='.repeat(72));
if (problems === 0) {
  console.log('  ✅ 全部检查通过');
} else {
  console.log(`  ❌ 发现 ${problems} 个问题，请修正后重跑`);
}
console.log('='.repeat(72));
console.log('\n  提示：本脚本只查结构一致性，不查"描述是否对得上界面"。');
console.log('        后者需要人对着真实界面核对，或补截图。');
process.exit(problems === 0 ? 0 : 1);
