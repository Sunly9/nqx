/**
 * ============================================================================
 *  垫片「外层 data 包装」覆盖检查
 * ============================================================================
 *  用法：node scripts/audit-shim-wrapping.js
 *
 *  【要解决的问题】
 *  旧后端有两种返回风格：
 *    A) res.json({ data: rows })     → 页面读 res.data.data
 *    B) res.json({ userInfo: ... })  → 页面读 res.data.userInfo
 *  垫片需要按「原路径」还原风格 A 的那层 data。
 *
 *  如果某个路径漏了配置，页面读 res.data.data 会拿到 undefined ——
 *  不报错、不崩溃，只是**列表永远是空的**。
 *
 *  这个脚本把「页面里实际用到的所有路径」与「垫片的包装配置」做集合差集，
 *  再结合每个路径的页面取值方式（是否读 res.data.data）判断哪些会出问题。
 * ============================================================================
 */

const fs = require('fs');
const path = require('path');

const MP = path.join(__dirname, '..', 'nqx-miniprogram');
const REQUEST_JS = path.join(MP, 'utils', 'request.js');

const src = fs.readFileSync(REQUEST_JS, 'utf8');

/**
 * 取出某个 const 声明块的原文。
 *
 * ⚠️ 这里踩过一个坑，值得写下来：
 *
 * 最初的正则是「匹配到 \n}; 或 \n]; 为止」。但 `DATA_WRAPPED_PATHS` 声明成
 *
 *     const DATA_WRAPPED_PATHS = new Set([
 *       '/api/xxx',
 *     ]);
 *
 * 结尾是 `\n]);` —— 既不是 `\n];` 也不是 `\n};`。
 * 于是正则匹配失败，退而求其次匹配到了**文件后面某个** `\n};`，
 * 把中间几千行源码全吞进去了。
 *
 * 表现是：脚本报告 DATA_WRAPPED_PATHS 有 97 项，里面混着
 * `Order created successfully`、`object`、`string` 甚至 `) + value` 这类垃圾；
 * 而真正的例外表 `DATA_NOT_WRAPPED` 被截断，导致
 * `/api/procurement/repair/apply` 被误报成"会取到 undefined"。
 *
 * **一次正则贪婪匹配，制造了一个看起来很像真 bug 的假警报。**
 * 所以现在显式支持两种声明形式，并且要求结尾必须是 `]` 或 `])` 后接 `;`。
 */
function block(name) {
  const re = new RegExp(
    'const\\s+' + name + '\\s*=\\s*(?:new\\s+Set\\()?\\[([\\s\\S]*?)\\n\\s*\\]\\)?\\s*;'
  );
  const m = src.match(re);
  return m ? m[1] : '';
}

/** 从声明块里取出所有字符串字面量 */
function strings(name) {
  const b = block(name);
  return (b.match(/'([^']+)'/g) || []).map(s => s.slice(1, -1));
}

const WRAPPED_PATHS = strings('DATA_WRAPPED_PATHS');
const WRAPPED_PREFIXES = strings('DATA_WRAPPED_PREFIXES');
const NOT_WRAPPED = strings('DATA_NOT_WRAPPED');

console.log('='.repeat(78));
console.log('  垫片「外层 data 包装」覆盖检查');
console.log('='.repeat(78));
console.log(`\nDATA_WRAPPED_PATHS    (${WRAPPED_PATHS.length}): ${WRAPPED_PATHS.join(', ')}`);
console.log(`DATA_WRAPPED_PREFIXES (${WRAPPED_PREFIXES.length}): ${WRAPPED_PREFIXES.join(', ')}`);
console.log(`DATA_NOT_WRAPPED      (${NOT_WRAPPED.length}): ${NOT_WRAPPED.join(', ')}\n`);

function isWrapped(p) {
  /*
   * 已知例外：/api/quiz/daily
   *
   * 它是本脚本唯一会误报的路径，原因值得记下来：
   * 页面确实读 res.data.data，但**那一层 data 不是垫片包的，而是这个接口
   * 自己的返回结构就长这样**：
   *
   *   Result.data = { hasAnswered: false, data: { id, question, options } }
   *
   * 垫片解包 Result.data 之后，res.data 已经是 { hasAnswered, data } 这一层，
   * 页面的 res.data.data 正好拿到题目。若把它加进 DATA_WRAPPED_PATHS，
   * 反而会多包一层，res.data.hasAnswered 变成 undefined，答题页直接坏掉。
   *
   * 所以"页面读 res.data.data"并不总意味着需要垫片包装 ——
   * 也可能是接口本身就这么设计的。这条只能靠人判断，脚本无法自动区分。
   * 与其让它每次都报一个假警报（假警报多了工具就没人看了），
   * 不如在这里显式记录成已知例外。
   */
  if (p === '/api/quiz/daily') return true;

  if (NOT_WRAPPED.indexOf(p) >= 0) return false;
  if (WRAPPED_PATHS.indexOf(p) >= 0) return true;
  for (const pre of WRAPPED_PREFIXES) {
    if (p.indexOf(pre) === 0) return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// 从页面源码里采集 (路径, 该方法是否读 res.data.data)
// ---------------------------------------------------------------------------
const pageFiles = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.js')) pageFiles.push(full);
  }
})(path.join(MP, 'pages'));

const usages = new Map(); // 路径 -> Set(读法)

for (const f of pageFiles) {
  const text = fs.readFileSync(f, 'utf8');
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/path:\s*[`'"]([^`'"]+)[`'"]/);
    if (!m) continue;
    let p = m[1];
    // 把模板变量与 :id 之类归一化，只保留静态前缀用于前缀匹配
    p = p.replace(/\$\{[^}]*\}/g, 'X').replace(/\/\d+/g, '/X');
    if (p.indexOf('/api/') !== 0) continue;

    // 往后找 30 行，看这个 success 回调里读的是 res.data.data 还是别的
    const window = lines.slice(i, i + 30).join('\n');
    /*
     * ⚠️ 匹配前必须先剥掉注释。
     *
     * 踩过的假警报：维修申请页（pages/procurement/repair/apply）的代码里
     * 有一句注释写着「所以这里读 res.data.type 而不是 res.data.data.type」，
     * 于是 /res\.data\.data/ 命中了**注释文本**，
     * 脚本判定"页面读 res.data.data 但垫片不包装" → 报了一个根本不存在的 bug。
     *
     * 剥注释的规则要小心别误伤字符串里的 `//`：
     * 行注释要求 `//` 前面是行首或空白，这样 `'https://...'` 不会被截断
     * （那里的 `//` 前面是冒号）。
     */
    const code = window
      .replace(/\/\*[\s\S]*?\*\//g, '')          // 块注释
      .replace(/(^|\s)\/\/[^\n]*/g, '$1');       // 行注释

    let read;
    if (/res\.data\.data/.test(code)) read = 'res.data.data';
    else if (/res\.data\.[a-zA-Z_]/.test(code)) {
      const mm = code.match(/res\.data\.([a-zA-Z_][\w]*)/);
      read = 'res.data.' + (mm ? mm[1] : '?');
    } else if (/res\.data\b/.test(code)) read = 'res.data';
    else read = '(未读响应)';

    if (!usages.has(p)) usages.set(p, new Set());
    usages.get(p).add(read);
  }
}

// ---------------------------------------------------------------------------
// 判定
// ---------------------------------------------------------------------------
console.log('='.repeat(78));
console.log('  逐个路径判定');
console.log('='.repeat(78) + '\n');

const problems = [];
const sorted = [...usages.keys()].sort();
for (const p of sorted) {
  const reads = [...usages.get(p)].join(' / ');
  const wrapped = isWrapped(p);
  // 读法里只要出现 res.data.data 就算"需要那层 data"
  const needsData = reads.indexOf('res.data.data') >= 0;

  let verdict;
  if (needsData && !wrapped) {
    verdict = '❌ 页面读 res.data.data，但垫片不包装 → 取值必为 undefined';
    problems.push(p);
  } else if (needsData && wrapped) {
    verdict = '✅ 页面读 res.data.data，垫片已包装';
  } else if (!needsData && wrapped) {
    verdict = '🟡 垫片包装了，但页面不读 .data（多余的包装，无害）';
  } else {
    verdict = '✅ 页面读 res.data.xxx，无需包装';
  }
  console.log(`  ${p}`);
  console.log(`      页面取值: ${reads}    垫片包装: ${wrapped ? '是' : '否'}`);
  console.log(`      ${verdict}\n`);
}

console.log('='.repeat(78));
if (problems.length === 0) {
  console.log('  ✅ 没有发现「读了 data 却没被包装」的路径');
} else {
  console.log(`  ❌ 有 ${problems.length} 个路径会导致页面拿到 undefined：`);
  problems.forEach(p => console.log(`      ${p}`));
  console.log('\n  修法：把上面这些路径加进 utils/request.js 的 DATA_WRAPPED_PATHS，');
  console.log('        或为其父目录加一条 DATA_WRAPPED_PREFIXES。');
  // 有问题时返回非零退出码，这样才能接进"改完必须全绿"的流程里
  process.exitCode = 1;
}
console.log('='.repeat(78));
