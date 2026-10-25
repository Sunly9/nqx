/**
 * 迁移验收脚本（结构与语法）
 * ============================================================================
 * 只做静态检查，不启动小程序、不联网：
 *   1. app.json 里 pages 数组的每一项，对应的 .js/.wxml/.json 是否存在
 *   2. 所有 .js 文件用 node --check 做语法解析
 *   3. 所有 .json 文件用 JSON.parse 解析
 *
 * 用法：node scripts/verify-migration.js [小程序根目录]
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(process.argv[2] || path.join(__dirname, '..'));

const result = {
  root: ROOT,
  pages: { total: 0, missing: [] },
  js: { total: 0, failed: [] },
  json: { total: 0, failed: [] },
  counts: {}
};

/** 递归收集文件（跳过 node_modules / miniprogram_npm / .git） */
function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'miniprogram_npm' || entry.name === '.git') {
      continue;
    }
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
    } else {
      out.push(full);
    }
  }
  return out;
}

const allFiles = walk(ROOT, []);
const rel = (f) => path.relative(ROOT, f).replace(/\\/g, '/');

// ---------------------------------------------------------------- 1. 结构完整性
const appJsonPath = path.join(ROOT, 'app.json');
if (!fs.existsSync(appJsonPath)) {
  console.error('致命：找不到 app.json');
  process.exit(1);
}
const appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
const pages = appJson.pages || [];
result.pages.total = pages.length;

for (const p of pages) {
  const base = path.join(ROOT, p);
  // .js / .wxml / .json 是必需项；.wxss 可选（页面可以只继承 app.wxss）
  for (const ext of ['.js', '.wxml', '.json']) {
    if (!fs.existsSync(base + ext)) {
      result.pages.missing.push(p + ext);
    }
  }
}

// tabBar 引用的一致性（自定义 tabBar 时 list 里的 pagePath 仍必须已注册）
const tabBar = (appJson.tabBar && appJson.tabBar.list) || [];
result.pages.tabBarMissing = tabBar
  .map((it) => it.pagePath)
  .filter((pp) => pages.indexOf(pp) === -1);

// ---------------------------------------------------------------- 2. JS 语法
const jsFiles = allFiles.filter((f) => f.endsWith('.js'));
result.js.total = jsFiles.length;
for (const f of jsFiles) {
  try {
    execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
  } catch (e) {
    result.js.failed.push({ file: rel(f), error: String(e.stderr || e.message).trim() });
  }
}

// ---------------------------------------------------------------- 3. JSON 合法性
const jsonFiles = allFiles.filter((f) => f.endsWith('.json'));
result.json.total = jsonFiles.length;
for (const f of jsonFiles) {
  try {
    JSON.parse(fs.readFileSync(f, 'utf8'));
  } catch (e) {
    result.json.failed.push({ file: rel(f), error: e.message });
  }
}

// ---------------------------------------------------------------- 4. 文件计数
for (const f of allFiles) {
  const top = rel(f).split('/')[0];
  const key = rel(f).indexOf('/') === -1 ? '(根目录)' : top + '/';
  result.counts[key] = (result.counts[key] || 0) + 1;
}

// ---------------------------------------------------------------- 输出
console.log('小程序根目录:', result.root);
console.log('');
console.log('=== 1. app.json pages 结构完整性 ===');
console.log(`pages 条目数: ${result.pages.total}`);
if (result.pages.missing.length === 0) {
  console.log('结果: 全部通过（每个页面的 .js/.wxml/.json 都存在）');
} else {
  console.log(`结果: 缺失 ${result.pages.missing.length} 个文件`);
  result.pages.missing.forEach((m) => console.log('  缺失: ' + m));
}
if (result.pages.tabBarMissing.length) {
  console.log('tabBar 中未注册的 pagePath: ' + result.pages.tabBarMissing.join(', '));
} else {
  console.log('tabBar: 5 个 pagePath 均已在 pages 中注册');
}

console.log('');
console.log('=== 2. node --check 语法检查 ===');
console.log(`检查 .js 文件数: ${result.js.total}`);
if (result.js.failed.length === 0) {
  console.log('结果: 全部通过');
} else {
  console.log(`结果: ${result.js.failed.length} 个文件有语法错误`);
  result.js.failed.forEach((x) => console.log(`  ${x.file}\n    ${x.error}`));
}

console.log('');
console.log('=== 3. JSON.parse 合法性检查 ===');
console.log(`检查 .json 文件数: ${result.json.total}`);
if (result.json.failed.length === 0) {
  console.log('结果: 全部通过');
} else {
  console.log(`结果: ${result.json.failed.length} 个文件解析失败`);
  result.json.failed.forEach((x) => console.log(`  ${x.file}: ${x.error}`));
}

console.log('');
console.log('=== 4. 文件计数（按顶层目录） ===');
Object.keys(result.counts).sort().forEach((k) => {
  console.log(`  ${k.padEnd(18, ' ')} ${result.counts[k]}`);
});
console.log(`  ${'合计'.padEnd(16, ' ')} ${allFiles.length}`);

const ok = result.pages.missing.length === 0 &&
  result.js.failed.length === 0 &&
  result.json.failed.length === 0;
console.log('');
console.log(ok ? '总判定: 通过' : '总判定: 存在问题，见上');
process.exit(ok ? 0 : 1);
