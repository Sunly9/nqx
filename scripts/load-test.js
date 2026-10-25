/**
 * ============================================================================
 *  并发压测：验证「无超卖」与「无超发」
 * ============================================================================
 *  用法：node scripts/load-test.js [并发数]
 *
 *  为什么不用 JMeter / wrk？
 *    本项目的验证重点不是"极限 QPS"，而是**并发下业务不变量是否成立**：
 *      · 库存会不会被扣成负数（超卖）
 *      · 积分会不会被扣成负数（超发）
 *      · 同一笔业务的重复请求会不会重复入账
 *    这三件事需要"精确统计成功/失败条数，再回查数据库"，用 Node 写最直接，
 *    而且不需要额外安装任何压测工具。
 *
 *  ⚠️ 重要认知：**并发测试的价值不在 QPS 数字，而在业务不变量的断言。**
 *      QPS 受机器、网络、JVM 预热影响很大，换个环境就完全不同；
 *      "200 并发下库存精确等于 0、积分永不为负"才是可复现、可辩论的结论。
 * ============================================================================
 */

const BASE = process.env.NQX_BASE || 'http://127.0.0.1:8080';
const CONCURRENCY = parseInt(process.argv[2] || '200', 10);

// ---------------------------------------------------------------------------
// 被测对象（与 setup 脚本写入的数据对应）
// ---------------------------------------------------------------------------
const PURCHASE_PRODUCT_ID = 3;   // 采购品，单价 1 元，库存被置为 100
const EXCHANGE_PRODUCT_ID = 5;   // 兑换品，80 积分/件，限兑已置为 0（不限）
const EXCHANGE_POINTS = 80;
const POINTS_BUDGET = 1000;      // 用户 2 的积分被置为 1000 → 理论最多成功 12 次

// ---------------------------------------------------------------------------
// HTTP 工具
// ---------------------------------------------------------------------------
async function http(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json; charset=utf-8' };
  if (token) {
    headers.Authorization = 'Bearer ' + token;
  }
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  // 后端统一返回 { code, msg, data, timestamp }，HTTP 状态恒为 200
  return res.json();
}

const post = (path, body, token) => http('POST', path, body, token);
const get = (path, token) => http('GET', path, undefined, token);

async function loginApp(code) {
  const r = await post('/app/auth/login', { code, role: 'lab' });
  if (!r.data || !r.data.token) {
    throw new Error(`小程序登录失败 code=${code} msg=${r.msg}`);
  }
  return r.data.token;
}

async function loginAdmin() {
  const r = await post('/admin/auth/login', { username: 'admin', password: 'Admin@123' });
  if (!r.data || !r.data.token) {
    throw new Error('管理员登录失败：' + r.msg);
  }
  return r.data.token;
}

// ---------------------------------------------------------------------------
// 并发执行器
//
// 用 Promise.all 一次性把 N 个请求全部发出去（而不是分批），
// 这是"瞬时并发"而非"持续压力"——正好用来触发竞争条件。
// ---------------------------------------------------------------------------
async function fireConcurrently(tasks) {
  const startedAt = Date.now();
  const results = await Promise.all(
    tasks.map((task) =>
      task().then(
        (value) => ({ ok: true, value }),
        (err) => ({ ok: false, error: err.message })
      )
    )
  );
  return { results, elapsedMs: Date.now() - startedAt };
}

function summarize(results) {
  let success = 0;
  const failMsgs = new Map();
  let transportErrors = 0;
  for (const r of results) {
    if (!r.ok) {
      transportErrors++;
      continue;
    }
    const body = r.value;
    if (body && body.code === 200) {
      success++;
    } else {
      const key = (body && body.msg) || '未知失败';
      failMsgs.set(key, (failMsgs.get(key) || 0) + 1);
    }
  }
  return { success, failed: results.length - success - transportErrors, transportErrors, failMsgs };
}

function printResult(title, results, elapsedMs, expectation) {
  const s = summarize(results);
  console.log(`\n【${title}】`);
  console.log(`  并发请求数 : ${results.length}`);
  console.log(`  成功       : ${s.success}`);
  console.log(`  业务失败   : ${s.failed}`);
  console.log(`  传输异常   : ${s.transportErrors}`);
  console.log(`  耗时       : ${elapsedMs} ms   → 吞吐约 ${Math.round((results.length / elapsedMs) * 1000)} req/s`);
  if (s.failMsgs.size > 0) {
    console.log('  失败原因分布：');
    for (const [msg, count] of [...s.failMsgs.entries()].sort((a, b) => b[1] - a[1])) {
      console.log(`    ${count} 次  ${msg}`);
    }
  }
  if (expectation) {
    console.log(`  期望       : ${expectation}`);
  }
  return s;
}

// ---------------------------------------------------------------------------
// 场景 A：库存防超卖
//
// 库存 100，同时打 200 个「买 1 件」的请求。
// 正确结果是**恰好 100 个成功、100 个报库存不足**，且库存精确归零。
// 若实现是"先查库存再判断再更新"，会看到成功数 > 100、库存变成负数。
// ---------------------------------------------------------------------------
async function scenarioOversell(tokens) {
  const tasks = [];
  for (let i = 0; i < CONCURRENCY; i++) {
    // 轮转使用 4 个用户，比"单用户狂刷 200 单"更接近真实场景
    const token = tokens[i % tokens.length];
    const addressId = (i % tokens.length) + 1;
    tasks.push(() =>
      post('/app/order/purchase', {
        source: 'direct',
        product_id: PURCHASE_PRODUCT_ID,
        quantity: 1,
        address_id: addressId
      }, token)
    );
  }
  const { results, elapsedMs } = await fireConcurrently(tasks);
  return { results, elapsedMs };
}

// ---------------------------------------------------------------------------
// 场景 B：积分防超发（防超扣）
//
// 用户 2 有 1000 积分，兑换品 80 积分/件，同时打 200 个兑换请求。
// 正确结果是**恰好 12 个成功**（12×80=960，第 13 个需要 1040 > 1000），
// 余额剩下 40，且**过程中永不出现负数**。
//
// 注意不能只看"最终余额 ≥ 0"：条件更新失败时事务会回滚，
// 最终余额本来就是对的。真正的风险在于"两个请求同时读到余额够、各扣一次"，
// 所以必须断言**成功条数恰好等于理论值**。
// ---------------------------------------------------------------------------
async function scenarioPointsOverdraft(token) {
  const tasks = [];
  for (let i = 0; i < CONCURRENCY; i++) {
    tasks.push(() =>
      post('/app/order/exchange', {
        product_id: EXCHANGE_PRODUCT_ID,
        quantity: 1,
        address_id: 2
      }, token)
    );
  }
  const { results, elapsedMs } = await fireConcurrently(tasks);
  return { results, elapsedMs };
}

// ---------------------------------------------------------------------------
// 场景 C：接口幂等（唯一索引兜底）
//
// 200 个请求带**同一个** request_id 去调整积分。
// 正确结果是 1 条流水、余额只变动一次。
// 这是"应用层判断在并发下必然失效、只有数据库唯一索引可靠"的直接验证。
// ---------------------------------------------------------------------------
async function scenarioIdempotent(adminToken, requestId) {
  const tasks = [];
  for (let i = 0; i < CONCURRENCY; i++) {
    tasks.push(() =>
      post('/admin/points/adjust', {
        user_id: 3,
        points: 7,
        remark: '并发幂等压测',
        request_id: requestId
      }, adminToken)
    );
  }
  const { results, elapsedMs } = await fireConcurrently(tasks);
  return { results, elapsedMs };
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
(async () => {
  console.log('='.repeat(72));
  console.log(`  纽启新生 · 并发压测   并发数=${CONCURRENCY}   目标=${BASE}`);
  console.log('='.repeat(72));

  const t0 = Date.now();
  const tokens = [];
  for (const code of ['dev:zhangsan', 'dev:lisi', 'dev:wangwu', 'dev:zhaoliu']) {
    tokens.push(await loginApp(code));
  }
  const adminToken = await loginAdmin();
  console.log(`\n登录完成：4 个小程序用户 + 1 个管理员，耗时 ${Date.now() - t0} ms`);

  // ---------- A ----------
  const a = await scenarioOversell(tokens);
  printResult('场景 A · 库存防超卖（库存 100，200 并发各买 1 件）',
    a.results, a.elapsedMs, '恰好 100 成功 / 100 报库存不足；库存精确归零，永不为负');

  // ---------- B ----------
  const b = await scenarioPointsOverdraft(tokens[1]);
  printResult('场景 B · 积分防超发（余额 1000，200 并发兑换 80 积分/件）',
    b.results, b.elapsedMs, '恰好 12 成功（12×80=960）；余额剩余 40，永不为负');

  // ---------- C ----------
  const requestId = 'loadtest-' + Date.now();
  const c = await scenarioIdempotent(adminToken, requestId);
  printResult('场景 C · 接口幂等（200 并发，同一个 request_id）',
    c.results, c.elapsedMs, '全部返回成功，但流水表只应新增 1 条、余额只加 7');

  console.log(`\n压测请求已完成，requestId=${requestId}`);
  console.log('请运行 scripts/load-test-verify.sql 核对数据库侧的不变量。\n');
})().catch((e) => {
  console.error('压测执行失败：', e);
  process.exit(1);
});
