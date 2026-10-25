/**
 * 统一请求层（小程序端）
 * ============================================================================
 * 这一个文件承担了四件事，直接决定了改造工作量的大小：
 *
 *   1. 把 wx.request 包装成 Promise，统一拼 URL、带 token、处理错误
 *   2. 解包后端统一返回体 Result<T>（{code,msg,data,timestamp}）
 *   3. token 失效（401）时静默重新登录，并自动重放原请求
 *   4. 提供 wx.cloud.callContainer 兼容垫片 —— 让旧页面几乎零改动
 *
 * ----------------------------------------------------------------------------
 * 【关于第 4 点的说明：为什么做兼容垫片，以及它的代价】
 *
 * 旧版的 27 个页面里，每一处请求都是这样写的：
 *
 *     wx.cloud.callContainer({
 *       env: 'your-cloudrun-env-id',
 *       service: 'app',
 *       path: '/api/orders/list',
 *       method: 'GET',
 *       data: { user_id: 1 },
 *       success: (res) => { ... res.data.orders ... },
 *       fail: (err) => { ... }
 *     })
 *
 * 要把它改成 wx.request，如果逐页手改，需要动 27 个文件、几十处调用点，
 * 而且极易漏改或改错（每处都要记得加 Authorization 头、都要解包 Result）。
 *
 * 兼容垫片把 callContainer 这个"形状"保留下来，只替换它的实现：
 * 页面里那一行 `wx.cloud.callContainer = ...` 由本文件在启动时接管，
 * **页面代码一行都不用改**。
 *
 * 代价（必须知道）：
 *   - 这是"猴子补丁"，覆盖了平台 API，语义上不够干净；
 *   - 新写的页面应当直接使用本文件导出的 request.get/post，不要再走垫片；
 *   - 垫片只是一个过渡层，等页面逐步迁移完成后应当删除。
 * 这是一个典型的"用一点技术债换大量改造工时"的取舍，且债是显式标出来的。
 * ============================================================================
 */

const env = require('./env');

const TOKEN_KEY = 'token';
const USER_KEY = 'userInfo';
const ROLE_KEY = 'role';

/** 后端约定的成功状态码 */
const CODE_SUCCESS = 200;
/** 后端约定的未登录状态码 */
const CODE_UNAUTHORIZED = 401;

/** 静默重登的并发去重：多个请求同时 401 时只重登一次 */
let reLoginPromise = null;

/**
 * 业务异常。
 * 把后端的 code 与 msg 带到 catch 里，页面可以按 code 做差异化处理。
 */
class BizError extends Error {
  constructor(code, msg) {
    super(msg || '请求失败');
    this.code = code;
    this.name = 'BizError';
  }
}

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function buildUrl(path) {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const base = env.API_BASE_URL.replace(/\/+$/, '');
  return base + (path.charAt(0) === '/' ? path : '/' + path);
}

/**
 * ============================================================================
 *  底层传输：本地直连 wx.request，云托管走原生 callContainer
 * ============================================================================
 *
 * 【为什么要抽这一层】
 *
 * 上面所有的业务逻辑 —— 路径映射、token、解包、401 静默重登 ——
 * 与"用什么协议把请求发出去"是**两件独立的事**。
 * 把传输抽出来之后：
 *
 *   · 本地开发用 wx.request 直连 127.0.0.1:8080；
 *   · 部署到微信云托管后切到 callContainer，走内网、**不需要配服务器域名**；
 *   · 页面代码、路径映射表、解包规则一行都不用改。
 *
 * 【callContainer 与 wx.request 的三处差异】
 *
 *   1. 参数名不同：callContainer 用 `path`（只给路径），wx.request 用 `url`；
 *   2. 需要额外 header：`X-WX-SERVICE` 指定调用哪个服务；
 *   3. 必须先 wx.cloud.init()（在 app.js 里做）。
 *
 *   响应形态是一样的：都有 `res.statusCode` 和 `res.data`，
 *   所以上层的错误处理完全不用改。
 *
 * 【callContainer 的两个限制（务必知道）】
 *
 *   · 请求体 ≤ 100KiB、返回包 ≤ 1000KiB —— 所以图片上传必须用 wx.uploadFile
 *     直传（见 utils/upload.js），导出必须返回下载链接而不是直接吐文件；
 *   · timeout 最大 15 秒（超过不生效）。
 */
/**
 * 原生 wx.cloud.callContainer 的引用。
 *
 * <h3>为什么必须单独存一份</h3>
 *
 * 垫片会把 `wx.cloud.callContainer` **替换成自己的函数**（因为旧页面全是这么调的）。
 * 而云托管模式下，transport() 又需要调用**真正的原生实现**。
 *
 * 如果直接写 `wx.cloud.callContainer(...)`，调到的就是垫片自己：
 *
 *     页面 → 垫片 → request() → transport() → 垫片 → request() → …
 *
 * 结果是无限递归，最终抛异常，页面上只看到一句笼统的「网络请求失败」——
 * 现象极像"网络不通"，实际是代码在自我调用。
 *
 * 所以：垫片安装前先把原生实现存到这个变量里，transport 只认它。
 */
let nativeCallContainer = null;

function transport(options) {
  const { path, method = 'GET', data, header, success, fail } = options;

  if (env.USE_CALL_CONTAINER) {
    if (/^https?:\/\//i.test(path)) {
      // 传绝对地址给 callContainer 是不合法的（它只接受路径）。
      // 这里显式报出来，而不是让它发一个诡异请求然后失败得莫名其妙。
      console.error('[request] 云托管模式下不允许绝对地址，path=', path);
      if (typeof fail === 'function') {
        fail({ errMsg: 'request:fail 云托管模式下不允许绝对地址' });
      }
      return;
    }
    if (!nativeCallContainer) {
      // 没拿到原生实现 —— 通常是 installCloudShim 还没跑，或 wx.cloud 不可用
      console.error('[request] 原生 callContainer 不可用，检查 wx.cloud.init 是否成功');
      if (typeof fail === 'function') {
        fail({ errMsg: 'request:fail 原生 callContainer 不可用' });
      }
      return;
    }
    nativeCallContainer({
      config: { env: env.CLOUDRUN_ENV_ID },
      path: path.charAt(0) === '/' ? path : '/' + path,
      method: method.toUpperCase(),
      data: data || {},
      // X-WX-SERVICE 是云托管的路由头，指明调用环境里的哪个服务
      header: Object.assign({ 'X-WX-SERVICE': env.CLOUDRUN_SERVICE }, header),
      success: success,
      fail: fail
    });
    return;
  }

  wx.request({
    url: buildUrl(path),
    method: method.toUpperCase(),
    data: data || {},
    header: header,
    success: success,
    fail: fail
  });
}

/**
 * 静默重新登录。
 *
 * 小程序没有 refresh token 机制，重新登录的方式就是再走一次 wx.login 拿新 code，
 * 换来新的登录态。用户是无感知的（不会弹任何界面）。
 */
function silentReLogin() {
  if (reLoginPromise) {
    return reLoginPromise;
  }

  reLoginPromise = new Promise((resolve, reject) => {
    wx.login({
      success: (loginRes) => {
        if (!loginRes.code) {
          reject(new BizError(-1, '微信登录未返回 code'));
          return;
        }
        // 重登也必须走同一套传输：
        // 云托管模式下如果用 wx.request 会打到公网（且没有合法域名配置），
        // 结果就是 token 一过期就永远重登不上，用户只能手动退出再登录。
        transport({
          path: '/app/auth/login',
          method: 'POST',
          header: { 'content-type': 'application/json' },
          data: {
            code: loginRes.code,
            // 带上用户上次选择的身份，保证重登后身份不丢
            role: wx.getStorageSync(ROLE_KEY) || 'lab'
          },
          success: (res) => {
            const body = res.data || {};
            if (body.code === CODE_SUCCESS && body.data && body.data.token) {
              wx.setStorageSync(TOKEN_KEY, body.data.token);
              if (body.data.userInfo) {
                wx.setStorageSync(USER_KEY, body.data.userInfo);
              }
              resolve(body.data.token);
            } else {
              // 重登也失败（如账号被禁用），清掉本地登录态并通知页面跳登录页
              wx.removeStorageSync(TOKEN_KEY);
              wx.removeStorageSync(USER_KEY);
              reject(new BizError(body.code, body.msg || '登录已失效，请重新登录'));
            }
          },
          fail: () => reject(new BizError(-1, '网络异常，无法重新登录'))
        });
      },
      fail: () => reject(new BizError(-1, '微信登录失败'))
    });
  });

  // 无论成功失败都要清掉，否则后续 401 会一直复用这个已 settle 的 Promise
  const clear = () => { reLoginPromise = null; };
  reLoginPromise.then(clear, clear);

  return reLoginPromise;
}

/**
 * 发起请求。
 *
 * @param {object}  options
 * @param {string}  options.url        接口路径，如 '/api/orders/list'
 * @param {string}  [options.method]   HTTP 方法，默认 GET
 * @param {object}  [options.data]     请求数据
 * @param {object}  [options.header]   额外请求头
 * @param {boolean} [options.silent]   为 true 时不自动弹 toast（页面自行处理错误提示）
 * @param {boolean} [options.retry]    内部使用：401 重放标记，防止无限递归
 * @returns {Promise<any>} 直接 resolve 出 Result 里的 data 部分
 */
function request(options) {
  const {
    url,
    method = 'GET',
    data,
    header = {},
    silent = false,
    retry = true
  } = options;

  return new Promise((resolve, reject) => {
    const token = getToken();
    const finalHeader = Object.assign(
      { 'content-type': 'application/json' },
      header
    );
    if (token) {
      finalHeader.Authorization = 'Bearer ' + token;
    }

    // 走哪个传输由 transport 决定（本地 wx.request / 云托管 callContainer），
    // 后面的业务处理两者完全一致
    transport({
      path: url,
      method: method,
      data: data || {},
      header: finalHeader,

      success: (res) => {
        const body = res.data;

        // 后端异常时可能返回 HTML 错误页（如反向代理的 502），此时 body 不是对象
        if (!body || typeof body !== 'object') {
          const msg = '服务暂时不可用（HTTP ' + res.statusCode + '）';
          if (!silent) wx.showToast({ title: msg, icon: 'none' });
          reject(new BizError(res.statusCode, msg));
          return;
        }

        // ---------- 成功 ----------
        if (body.code === CODE_SUCCESS) {
          resolve(body.data);
          return;
        }

        // ---------- 未登录：静默重登后重放原请求 ----------
        if (body.code === CODE_UNAUTHORIZED && retry) {
          silentReLogin().then(
            () => {
              // retry=false 防止重登后仍然 401 造成无限递归
              request(Object.assign({}, options, { retry: false })).then(resolve, reject);
            },
            (err) => {
              if (!silent) {
                wx.showToast({ title: err.message || '登录已失效', icon: 'none' });
              }
              reject(err);
            }
          );
          return;
        }

        // ---------- 其他业务错误 ----------
        if (!silent) {
          wx.showToast({ title: body.msg || '操作失败', icon: 'none' });
        }
        reject(new BizError(body.code, body.msg));
      },

      fail: (err) => {
        /*
         * 网络层失败。这里必须**区分两种原因**，否则提示会把排查带偏。
         *
         * 实际踩过：后端没启动，控制台是 `net::ERR_CONNECTION_REFUSED`，
         * 而提示写的是"请检查是否勾选不校验合法域名" —— 使用者去检查域名设置，
         * 发现明明勾了，于是怀疑是代码坏了。**一句错方向的提示，
         * 比没有提示更浪费时间。**
         *
         * 两者的 errMsg 特征完全不同，可以分辨：
         *   · 域名未配置  → 含 "domain" / "not in domain list" / "不在以下 request 合法域名列表中"
         *   · 连不上服务  → 含 "fail" 且没有域名关键词（后端没起 / 端口错 / 防火墙）
         *   · 超时        → 含 "timeout"
         */
        const errMsg = String((err && (err.errMsg || err.message)) || '');
        const lower = errMsg.toLowerCase();

        let msg;
        if (lower.indexOf('domain') >= 0 || errMsg.indexOf('合法域名') >= 0) {
          msg = '域名未配置：请在开发者工具「详情 → 本地设置」勾选"不校验合法域名"';
        } else if (lower.indexOf('timeout') >= 0) {
          msg = '请求超时：后端可能卡住了，请看后端窗口是否有报错';
        } else if (lower.indexOf('connrefused') >= 0 || lower.indexOf('request:fail') >= 0) {
          // 最常见的开发场景：微信开发者工具开着，但后端没启动
          msg = env.isDebug
            ? '连不上后端（' + env.API_BASE_URL + '）：请先启动后端 —— 双击「启动后端.bat」或「启动全部.bat」'
            : '网络连接失败，请稍后重试';
        } else {
          msg = env.isDebug ? '网络请求失败：' + (errMsg || '原因未知') : '网络连接失败';
        }

        if (!silent) wx.showToast({ title: msg, icon: 'none' });
        console.error('[request] 请求失败 | url=' + buildUrl(url) +
          ' | errMsg=' + (errMsg || '(无)') +
          ' | 提示=' + msg, err);
        reject(new BizError(-1, msg));
      }
    });
  });
}

// ============================================================================
// 旧接口路径 → 新接口路径 映射
// ============================================================================
/**
 * 旧版后端的接口路径与新后端的并不一致（新后端按 RESTful 风格重新组织过）。
 *
 * 若不做映射，旧页面请求 `/api/orders/list` 会直接 404。
 * 有了这张表，页面里的 path 一个字都不用改。
 *
 * 值为字符串时表示"只换路径"；为对象时可同时覆盖 HTTP 方法。
 *
 * 注意：这里只列出**新后端已经实现**的接口。
 * 尚未实现的接口不要提前加进来，否则请求会打到 404 上，
 * 反而让人误以为"映射没生效"。未映射的路径保持原样发送，
 * 后端会返回 404，日志里能清楚看到是哪些接口还没做。
 */
const PATH_ALIAS = {
  // ---------- 认证 ----------
  '/api/login': '/app/auth/login',
  '/api/procurement/login': '/app/auth/login',
  '/api/user/info': { path: '/app/auth/info', method: 'GET' },
  '/api/procurement/user/info': { path: '/app/auth/info', method: 'GET' },
  '/api/user/update': { path: '/app/auth/profile', method: 'PUT' },
  '/api/procurement/user/update': { path: '/app/auth/profile', method: 'PUT' },

  // ---------- 商品 ----------
  '/api/procurement/products': { path: '/app/product/page', method: 'GET' },
  /*
   * 商品分类树（采购端首页的分类导航）。
   *
   * 这条之前**根本不存在**，而管理后台早就有一个「图标地址」字段能填图片 ——
   * 于是出现了一个很典型的问题：管理员填了图标、数据库也真的存了，
   * 但小程序里没有任何地方展示它，看起来像"填了没用"。
   *
   * 数据不是假的，缺的是消费者。现在补上。
   *
   * ⚠️ 路径必须写成 `/app/product/category/list`，**不是** `/app/product-category/list`。
   *
   * 控制器类名叫 `AppProductCategoryController`，但它的 `@RequestMapping` 是
   * `/app/product/category` —— 两者只差一个连字符和斜杠。
   * 我第一版照着类名猜成了 `/app/product-category/list`，结果后端返回
   * **404「资源不存在」**，而前端只表现为"分类栏里什么都没有"。
   *
   * 教训：**接口路径必须去读 `@RequestMapping` 注解，不能按类名/文件名推断。**
   * 推断错了不会报错，只会安静地少一块数据。
   */
  '/api/procurement/categories': { path: '/app/product/category/list', method: 'GET' },
  /*
   * 注意：/api/points/goods 与 /api/procurement/goods 这两条**原本也写在这里**，
   * 都被指到 /app/product/page（即全部上架商品）。
   * 本次迁移把它们挪到了下面的「积分」分组，改指 /app/points/goods ——
   * 因为新后端已经把「只返回可兑换商品（productType ∈ {2,3}）」这个筛选
   * 固化在该接口里，而这两个旧路径一个语义是「积分商品」、
   * 一个在采购端积分页用（采购端积分商城）。
   * 若继续指向 /app/product/page，积分页会把只能现金购买的普通商品也列出来，
   * 用户点了兑换必然失败。
   */

  // ---------- 回收品类 ----------
  '/api/recycle/categories': { path: '/app/recycle-category/list', method: 'GET' },

  // ---------- 回收单 ----------
  '/api/orders/create': { path: '/app/order/recycle', method: 'POST' },
  /*
   * ⚠️ 刻意**不映射** '/api/orders/settle'。
   *
   * 旧版把「结算发积分」做成了小程序端接口：POST /api/orders/settle
   *   { order_id, actual_weight }
   * 且没有任何鉴权。这意味着**任何用户都能给自己刷积分** ——
   * 随便传一个 actual_weight = 99999，就能凭空拿到 999 万积分。
   * 这是旧版最严重的漏洞之一（积分等同于平台负债）。
   *
   * 新设计里结算是运营动作，只存在于 /admin/order/{orderNo}/confirm-recycle，
   * 受 RBAC 权限码保护。因此该路径**故意不映射** ——
   * 页面调用它会收到 404，这是预期行为，提示开发者把它挪到管理后台。
   * 这属于"安全修复导致的接口下线"，不是功能回退。
   */
  '/api/orders/list': { path: '/app/order/page', method: 'GET' },

  // ---------- 采购单（旧版路径） ----------
  /*
   * ⚠️ 旧版的 pay / cancel / receive 把订单标识放在**请求体**里：
   *       POST /api/procurement/orders/pay   { order_id: 123 }
   * 而新接口把它放在路径上：
   *       POST /app/order/123/pay
   *
   * 这种「体参数 → 路径参数」的改写无法用静态映射表表达
   * （映射表只能整条替换路径），因此单独用下面的 ACTION_ALIAS 机制处理。
   */
  '/api/procurement/orders/list': { path: '/app/order/page', method: 'GET' },
  '/api/procurement/orders/create': { path: '/app/order/purchase', method: 'POST' },

  /*
   * ⚠️ 同样刻意**不映射** '/api/procurement/orders/ship'。
   *
   * 旧版把它做成了小程序端接口，任何能打开采购单页面的用户都能点"发货"。
   * 新设计里发货是管理端动作：/admin/order/{orderNo}/deliver，
   * 受权限码 order:purchase:deliver 保护。
   * 与 /api/orders/settle 同理 —— 这是安全修复导致的接口下线。
   */

  // ---------- 收货地址 ----------
  // 旧版两端用的前缀不同：采购端 /api/procurement/address/、回收端 /api/address/
  '/api/procurement/address/list': { path: '/app/address/list', method: 'GET' },
  /*
   * 旧版地址列表的回收端路径是 /api/address/list，但**精确映射表里没有它**，
   * 结果会被下面的前缀规则 '/api/address/' 命中、拼成 '/app/address/list' ——
   * 终点虽然正确，方法却被前缀规则改写成 PUT，而新接口只接受 GET。
   * 因此在精确表里补一条，明确 method=GET。
   */
  '/api/address/list': { path: '/app/address/list', method: 'GET' },

  // ---------- 积分 ----------
  '/api/points/history': { path: '/app/points/record/page', method: 'GET' },
  '/api/procurement/points/history': { path: '/app/points/record/page', method: 'GET' },
  '/api/points/account': { path: '/app/points/account', method: 'GET' },
  /*
   * 积分兑换。
   *
   * 旧版把它做成「积分模块」的接口（POST /api/points/exchange），
   * 新设计里兑换单与采购单共用一套订单表与状态机（待发货 → 发货 → 收货），
   * 因此落在订单模块下：POST /app/order/exchange（见 docs/07 接口映射表）。
   * 两端（回收端 / 采购端）在旧版是两个路径，现在统一成一个。
   */
  '/api/points/exchange': { path: '/app/order/exchange', method: 'POST' },
  '/api/procurement/points/exchange': { path: '/app/order/exchange', method: 'POST' },
  /*
   * 积分商品列表。
   *
   * 回收端页面调的是 /api/points/goods，采购端页面调的是 /api/procurement/goods，
   * 旧版这是两张表两套接口（exchange_goods / procurement_goods）。
   * 新设计合并成一张 product 表，用 productType 区分：
   *   2 = 仅积分兑换，3 = 采购与兑换皆可。
   * 新后端已经把这个筛选固化在 GET /app/points/goods 里
   * （EXCHANGE_PRODUCT_TYPES = [2, 3]），所以这里直接指过去即可，
   * 不需要在垫片里再拼 productTypes 查询参数 —— 查询条件由服务端决定，
   * 不让客户端有机会传 productType=1 把普通采购商品混进兑换列表。
   *
   * 这两条**覆盖了上面「商品」分组里原本指向 /app/product/page 的同名键**
   * （JS 对象字面量后写的键胜出）。本次已把上面那两条删掉，
   * 避免留下一条永远不生效、却让人以为「积分商品=全部商品」的死配置。
   */
  '/api/points/goods': { path: '/app/points/goods', method: 'GET' },
  '/api/procurement/goods': { path: '/app/points/goods', method: 'GET' },

  // ---------- 商品评价 ----------
  /*
   * 提交评价（整单批量提交，见 ProductReviewSubmitDTO）。
   * 旧版是 POST /api/procurement/reviews/add，新版按资源命名改为 POST /app/product/review。
   *
   * ⚠️ 新后端的 AppProductController 目前只有 page / hot / {id} 三个方法，
   * 这个接口**尚未实现**（ProductReviewSubmitDTO 与 ProductReviewVO 已经写好，
   * 缺的是 controller + service）。当前请求会打到 404，
   * 等后端补上后本映射立即生效，页面不用再改。
   * 详见 docs/07-小程序端改造方案.md 第六节「仍需后端补齐的接口」。
   */
  '/api/procurement/reviews/add': { path: '/app/product/review', method: 'POST' },
  /*
   * 查询某商品的评价列表（GET /api/procurement/products/{id}/reviews
   * → GET /app/product/{id}/reviews）由前缀映射 PATH_PREFIX_ALIAS 覆盖，
   * 这里不再重复列一条。但它是「带路径参数 + 用不到 id」的写法，
   * 只能靠前缀规则，因此**不要**把 '/api/procurement/products/' 这条前缀删掉。
   */

  // ---------- 维修（申请 / 列表 / 详情） ----------
  /*
   * ⚠️ 与评价同理，新后端暂无 AppRepairController，
   * 下面三条当前都会 404（RepairApplyVO 已写好，缺 controller + service）。
   *
   * 【路径以任务给定为准，与旧文档不一致，需要确认】
   *   - 本表按任务给定写成 /app/repair/list、/app/repair/apply、/app/repair/{id}；
   *   - 而 docs/07-小程序端改造方案.md 的接口映射表写的是
   *     GET /app/repair/page（列表）。两者只能留一个，以后端最终实现为准。
   *   - 列表路径一旦定下来，这里改成对应的一条即可（前缀映射仍按 /app/repair/ 走）。
   */
  '/api/procurement/repair/list': { path: '/app/repair/list', method: 'GET' },
  '/api/procurement/repair/apply': { path: '/app/repair/apply', method: 'POST' },

  /*
   * ---------- 每日问答 ----------
   *
   * 旧版这两个接口的返回结构很特别，需要注意：
   *   GET  /api/quiz/daily  → { hasAnswered, data: question }
   *   POST /api/quiz/submit → { isCorrect, pointsAwarded, correctIndex, explanation }
   *
   * 新版把这两个结构原样搬进了 DailyQuizVO / QuizSubmitVO，
   * 因此**不能**加进 DATA_WRAPPED_PATHS —— 垫片解包 Result.data 之后，
   * res.data 拿到的就已经是旧版那一层了。若误加会多包一层，
   * 页面上 res.data.hasAnswered 会变成 undefined。
   */
  '/api/quiz/daily': { path: '/app/question/today', method: 'GET' },
  '/api/quiz/submit': { path: '/app/question/submit', method: 'POST' },

  // ---------- 内容 ----------
  '/api/tips/random': { path: '/app/content/tip/random', method: 'GET' },
  '/api/faqs': { path: '/app/content/faq', method: 'GET' },
  '/api/procurement/faqs': { path: '/app/content/faq', method: 'GET' },
  '/api/announcements/active': { path: '/app/content/announcement', method: 'GET' },
  '/api/config/logo': { path: '/app/content/setting', method: 'GET' }
};

/**
 * 旧后端用「外层再包一层 data」的接口路径集合。
 *
 * 为什么需要这个？旧后端有这么两种返回风格：
 *   A) res.json({ data: rows })      → 前端读 res.data.data
 *   B) res.json({ userInfo: ... })   → 前端读 res.data.userInfo
 * 而 callContainer 会把整个 HTTP body 放进 res.data，
 * 因此风格 A 的接口，前端代码里写的是 res.data.data。
 *
 * 新后端统一返回干净的业务对象（不再套外层 data），
 * 由垫片在这里按「原路径」还原出旧结构。
 *
 * 关键点：这里判断的是**原路径**（映射前的），
 * 因为要还原的是旧接口的返回风格，与映射后的新路径无关。
 */
const DATA_WRAPPED_PATHS = new Set([
  '/api/tips/random',
  '/api/faqs',
  '/api/procurement/faqs',
  '/api/announcements/active',
  '/api/orders/list',
  // 积分：goods 与 history 两个页面读的都是 res.data.data（数组），
  // 而新后端返回的是 PageResult（{records,total,current,size,pages}）或干净数组，
  // 必须由垫片补上旧版那一层 data。
  // ⚠️ 注意：这里只还原「外层 data」这一层。新后端的分页体本身不是数组，
  //    包完仍是 {data:{records:[...]}}，页面里的 res.data.data.map(...) 会拿到
  //    一个对象而不是数组 —— 属于已知的新旧分页契约差异，页面侧尚未适配。
  '/api/points/goods',
  '/api/procurement/goods',
  '/api/points/history',
  '/api/procurement/points/history',
  // 评价列表 / 维修列表：页面写的是 res.data.data.map(...)，
  // 与商品列表同一类返回风格。
  // 带 id 的评价路径（/api/procurement/products/12/reviews）无法用精确键表达，
  // 已由下面 DATA_WRAPPED_PREFIXES 的 '/api/procurement/products/' 前缀覆盖。
  '/api/procurement/repair/list',
  /*
   * 采购订单列表：页面读 res.data.data.map(...)。
   * 漏了它的话，采购订单页拿到的是整个 PageResult 对象。
   */
  '/api/procurement/orders/list',
  /*
   * 商品列表（商城主页）：这条是「精确路径」而不是前缀 ——
   * 带 id 的详情走下面的 '/api/procurement/products/' 前缀。
   * 之前遗漏了它，导致商城主页一定空白。
   */
  '/api/procurement/products',
  /*
   * 商品分类树：页面同样读 res.data.data（旧版形状），
   * 所以必须在这里补上外层 data，否则拿到的会是整个 Result 对象。
   */
  '/api/procurement/categories'
]);

/**
 * 前缀形式的「需要外层 data 包装」的路径，用于带路径参数的接口。
 * 例如旧版 GET /api/orders/12 返回 { data: rows[0] }。
 */
const DATA_WRAPPED_PREFIXES = [
  '/api/orders/',
  '/api/address/',
  /*
   * ⚠️ 采购端的地址路径原来**漏了**，这是一个真实且隐蔽的 bug：
   *
   *   旧版两类客户端用了不同的前缀 —— 回收端 /api/address/，
   *   采购端 /api/procurement/address/。
   *   上面只配了回收端那一条，于是采购端（role=procurement）的地址列表
   *   拿到的是未包装的裸数组，页面读 res.data.data 得到 undefined，
   *   列表永远显示「暂无收货地址」。
   *
   * 恶劣之处：请求成功、状态码 200、数据库里也确实有数据，
   * 只是页面读不到 —— 看起来完全像「地址没存进去」。
   *
   * 教训：**同一个业务两端各有一套路径前缀时，配置必须成对写。**
   *      只配一端，出问题的恰恰是没配的那一端，而且不报错。
   */
  '/api/procurement/address/',
  /*
   * 采购端商品：这条前缀同时覆盖两件事
   *   GET /api/procurement/products/12              → 详情，页面读 res.data.data
   *   GET /api/procurement/products/12/reviews      → 评价列表，页面读 res.data.data
   * 两者在旧后端都返回 { data: ... }，所以一条前缀就够了。
   * 前缀判断发生在映射**之前**（这里只关心旧接口的返回风格），
   * 因此虽然新终点分别是 /app/product/12 与 /app/product/12/reviews，判断不受影响。
   */
  '/api/procurement/products/',
  /*
   * 采购端订单详情：GET /api/procurement/orders/12 → 页面读 res.data.data。
   *
   * 同样原来漏了（只配了回收端的 /api/orders/）。后果是采购订单详情页
   * 拿到 undefined，整页空白。
   *
   * ⚠️ 这条前缀会连带命中 /api/procurement/orders/list、/create、/pay 等，
   *    所以必须在 DATA_NOT_WRAPPED 里逐个排除那些「不返回外层 data」的路径
   *    （list 走精确白名单没问题，create 读的是 res.data.message，
   *     pay/cancel/receive 页面根本不读响应）。
   *    **加一条前缀就必须同时检查它的所有子路径** —— 这是前缀映射的固有代价。
   */
  '/api/procurement/orders/',
  // 维修详情。列表与申请已分别用精确键放进白名单/例外表，不受此条影响。
  // 说明：当前**没有页面调用** /api/procurement/repair/{id}（只有申请与列表），
  // 这条是按接口契约预留的；等维修详情页做出来时它会自动生效。
  '/api/procurement/repair/'
];

/**
 * 前缀命中但**不应**包装的例外。
 *
 * `/api/orders/create` 旧版返回的是 `{ message: 'Order created successfully' }`，
 * 没有外层 data，前端读的是 `res.data.message`，包装了反而不对。
 * `/api/orders/settle` 是已被安全下线、故意不映射的接口，包装没有意义。
 */
const DATA_NOT_WRAPPED = new Set([
  '/api/orders/create',
  '/api/orders/settle',
  /*
   * 维修申请：页面读的是 res.data.type（warranty / paid 决定提示文案），
   * 没有外层 data。它会命中 '/api/procurement/repair/' 前缀，
   * 必须在例外表里排除，否则会变成 res.data.data.type，页面提示文案恒为「需付费」。
   */
  '/api/procurement/repair/apply',
  /*
   * 采购下单：页面读的是 res.data.message（失败时展示后端提示），
   * 没有外层 data。它会被 '/api/procurement/orders/' 前缀命中，必须排除。
   */
  '/api/procurement/orders/create',
  /*
   * 支付 / 取消 / 收货：页面只看 HTTP 成功与否，不读响应体内容
   * （旧后端这些接口返回 { message } 或空）。包装与否都不影响功能，
   * 但显式列出来可以避免"前缀命中后到底包没包"这种需要推理的疑问 ——
   * 配置里写清楚比让人去读实现更省事。
   */
  '/api/procurement/orders/pay',
  '/api/procurement/orders/cancel',
  '/api/procurement/orders/receive',
  '/api/procurement/orders/ship'
]);

/**
 * 判断某个旧路径的响应是否需要还原成 `{ data: ... }` 结构。
 *
 * 判断依据是**映射前的原路径** —— 要还原的是旧接口的返回风格，
 * 与映射后的新路径无关。
 */
function isDataWrapped(path) {
  if (!path) {
    return false;
  }
  if (DATA_NOT_WRAPPED.has(path)) {
    return false;
  }
  if (DATA_WRAPPED_PATHS.has(path)) {
    return true;
  }
  for (let i = 0; i < DATA_WRAPPED_PREFIXES.length; i++) {
    if (path.indexOf(DATA_WRAPPED_PREFIXES[i]) === 0) {
      return true;
    }
  }
  return false;
}

/**
 * 把新后端的「分页体」还原成旧后端的「裸数组」。
 *
 * <h3>这是整个垫片里最要紧的一处适配</h3>
 *
 * 旧后端的列表接口一律这么返回：
 * <pre>
 *   res.json({ data: rows })      // rows 是数组
 * </pre>
 * 页面里写的是：
 * <pre>
 *   res.data.data.map(...)  /  .filter(...)  /  .length
 * </pre>
 *
 * 新后端统一返回 `PageResult`：
 * <pre>
 *   { records: [...], total, current, size, pages }
 * </pre>
 * 经过外层 data 包装后变成 `{ data: { records: [...] } }`，
 * 页面拿到的就是**一个对象而不是数组** —— `.map` 直接抛
 * `TypeError: res.data.data.map is not a function`，列表页整页白屏。
 *
 * 受影响的接口不少于 7 个（订单列表、采购订单列表、商品列表、
 * 积分商品、积分流水、评价列表、维修列表），是本次迁移中影响面最广的问题。
 *
 * <h3>为什么在垫片里解，而不是改页面</h3>
 * 改页面意味着要动 7 个页面的十几处取值，而且以后每加一个列表接口
 * 都要记得改。放在这里是一处修复、全部生效。
 *
 * <h3>代价</h3>
 * 页面拿不到 `total`，因此**无法做服务端分页** —— 但旧后端本来就没返回 total，
 * 旧页面也从来没用过服务端分页（都是本地渲染全部）。所以这不是能力退化，
 * 而是精确还原了旧契约。将来要做真分页时，应新增 `/app/v2/**` 之类的
 * 新路径让新页面直接用 PageResult，而不是继续迁就旧写法。
 */
function unwrapPagePayload(payload) {
  if (payload && typeof payload === 'object' && Array.isArray(payload.records)) {
    return payload.records;
  }
  return payload;
}

/**
 * 把所有以 `/files/` 开头的字符串补成完整地址。
 *
 * <h3>为什么需要这一步</h3>
 *
 * 后端存的是**相对路径**（`/files/product/1.png`），这是对的 ——
 * 写死 `http://127.0.0.1:8080` 之后，换台机器、换个端口、部署到服务器，
 * 数据库里的图片地址就全挂了。
 *
 * 但小程序的 `<image src="/files/product/1.png">` 会把这个路径当成
 * **小程序包内的文件**去找，而不是向服务器请求 —— 结果就是图片永远加载不出来。
 * 浏览器里没这个问题（相对路径基于当前域名解析），所以后台能正常显示，
 * 只有小程序端会白图。
 *
 * <h3>为什么按值判断而不是按字段名</h3>
 *
 * 不维护「哪些字段是图片」的清单，而是**递归检查每个字符串值是否以 /files/ 开头**。
 * 这样无论将来后端新增多少个图片字段（商品封面、评价晒图、故障图片、头像……），
 * 都会自动被补全，不需要回来改这里。
 *
 * ⚠️ 已知边界：如果某个字段的内容恰好是用户输入、且以 `/files/` 开头，
 * 它也会被补全。实际影响可以忽略（那会变成一个打不开的 URL），
 * 换来的是「永远不用维护字段清单」。
 */
function rewriteAssetUrls(value, depth) {
  if (depth === undefined) {
    depth = 0;
  }
  if (depth > 8 || value === null || value === undefined) {
    return value;
  }
  if (typeof value === 'string') {
    return value.indexOf('/files/') === 0
      ? env.API_BASE_URL.replace(/\/+$/, '') + value
      : value;
  }
  if (Array.isArray(value)) {
    const arr = new Array(value.length);
    for (let i = 0; i < value.length; i++) {
      arr[i] = rewriteAssetUrls(value[i], depth + 1);
    }
    return arr;
  }
  if (typeof value === 'object') {
    const out = {};
    const keys = Object.keys(value);
    for (let i = 0; i < keys.length; i++) {
      out[keys[i]] = rewriteAssetUrls(value[keys[i]], depth + 1);
    }
    return out;
  }
  return value;
}

/**
 * 前缀映射，用于处理带路径参数的接口。
 * 例如 `/api/procurement/products/12` → `/app/product/12`。
 * 数组顺序即匹配优先级，把更具体的前缀放在前面。
 *
 * 注意：精确映射（PATH_ALIAS）优先于前缀映射，所以
 * `/api/orders/list` 不会被下面的 `/api/orders/` 规则抢占。
 */
const PATH_PREFIX_ALIAS = [
  { from: '/api/procurement/products/', to: '/app/product/', method: 'GET' },
  /*
   * 采购单详情：GET /api/procurement/orders/12 → GET /app/order/12。
   *
   * 这条是本次通读页面时**新发现**的缺失映射：订单列表页跳详情、
   * 订单详情页 onLoad、评价页查订单三处都在用（见 pages/procurement/orders/detail/index.js:32、
   * pages/procurement/orders/review/index.js:27）。
   * 没有它，采购端点进任何订单都是 404。
   *
   * 与下面的 ACTION_ALIAS 不冲突：
   *   - pay / cancel / receive 是完整路径（/api/procurement/orders/pay），
   *     走精确映射表 → 前缀不参与；
   *   - 详情是「前缀 + 主键」，才落到这条上。
   * 新接口的路径参数同时接受主键 id 与订单号（findByIdOrOrderNo 会自己判断），
   * 所以旧页面传的是主键也无需转换。
   */
  { from: '/api/procurement/orders/', to: '/app/order/', method: 'GET' },
  // 维修详情：GET /api/procurement/repair/12 → GET /app/repair/12。
  // 放在 products 规则之后，两条前缀互不重叠（products 与 repair 是不同段）。
  // 注意这条会连带覆盖「不存在」的 /api/procurement/repair/apply、
  // /api/procurement/repair/list —— 但它们在上面的精确表里已先行命中，
  // 精确映射优先于前缀映射（见 resolveAlias 的实现顺序），因此不会被改写。
  { from: '/api/procurement/repair/', to: '/app/repair/', method: 'GET' },
  /*
   * 收货地址的增 / 改 / 删 —— 旧版路径里带 detail 动作名，新版是干净的 REST：
   *
   *   POST   /api/address/add                  → POST   /app/address
   *   PUT    /api/address/edit/{id}            → PUT    /app/address
   *   DELETE /api/address/delete/{id}          → DELETE /app/address/{id}
   *   （采购端 /api/procurement/address/… 同理）
   *
   * 【为什么要逐条列，而不是用通配】
   * 旧版把「动作」和「标识」都放在路径里（add 无标识、edit/delete 才带 id），
   * 而新接口把标识放进请求体（PUT 的 AddressSaveDTO.id）。
   * 前缀映射只能做「保留尾部」的机械替换，表达不了「add 要去掉尾部、
   * edit 要去掉 'edit' 这一段」这种差异，所以这里用三条精确前缀各自处理。
   */
  { from: '/api/procurement/address/add', to: '/app/address', method: 'POST' },
  { from: '/api/address/add', to: '/app/address', method: 'POST' },
  { from: '/api/procurement/address/edit/', to: '/app/address', method: 'PUT' },
  { from: '/api/address/edit/', to: '/app/address', method: 'PUT' },
  /*
   * 删除是**唯一能机械替换**的一条：
   *   DELETE /api/address/delete/12 → DELETE /app/address/12
   * 尾部的主键恰好就是新接口路径参数的取值，所以前缀映射够用。
   *
   * ⚠️ 这几条 delete 规则放在 edit 之后，只是为了让「同前缀家族」挨在一起，
   * 实际两条前缀互不重叠（delete 与 edit 是不同段），顺序不影响结果；
   * 但仍然遵循「具体在前、宽泛在后」的写法，减少以后加规则时的踩坑。
   */
  { from: '/api/procurement/address/delete/', to: '/app/address/', method: 'DELETE' },
  { from: '/api/address/delete/', to: '/app/address/', method: 'DELETE' },
  { from: '/api/orders/', to: '/app/order/', method: 'GET' }
];

/**
 * 「请求体参数 → 路径参数」的改写规则。
 *
 * <h3>为什么需要单独一张表</h3>
 *
 * 旧版采购端的支付/取消/收货把订单标识放在**请求体**里：
 * <pre>
 *   POST /api/procurement/orders/pay   { order_id: 123 }
 * </pre>
 * 而新接口按 REST 风格把它放在路径上：
 * <pre>
 *   POST /app/order/123/pay
 * </pre>
 *
 * {@link PATH_ALIAS} 只能整条替换路径，表达不了"把 body 里的某个字段搬到路径上"。
 * 与其在页面里逐个改调用方式（那要动 6 个页面的十几处代码，
 * 而且以后每加一个类似接口都要再改），不如把这种改写能力做成垫片的一项正式职责 ——
 * <b>适配层的价值就在于把差异收敛到一个地方。</b>
 *
 * <h3>订单标识是主键 id，不是订单号</h3>
 *
 * 旧版传的是主键 id。新接口的路径参数同时接受主键与订单号
 * （{@code findByIdOrOrderNo} 会自己判断），所以这里不需要额外转换。
 */
const ACTION_ALIAS = [
  { from: '/api/procurement/orders/pay', to: '/app/order/{id}/pay', method: 'POST' },
  { from: '/api/procurement/orders/cancel', to: '/app/order/{id}/cancel', method: 'POST' },
  { from: '/api/procurement/orders/receive', to: '/app/order/{id}/confirm', method: 'POST' }
];

/**
 * 从请求体里提取订单标识。
 *
 * 旧版各页面用的字段名并不统一（order_id / orderId / id），
 * 因此逐个尝试而不是只认一个 —— 这是兼容层该有的容错：
 * 它能容忍旧代码的不一致，而不必反过来要求旧代码先统一。
 */
function extractOrderKey(data) {
  if (!data || typeof data !== 'object') {
    return null;
  }
  const candidates = ['order_id', 'orderId', 'order_no', 'orderNo', 'id'];
  for (let i = 0; i < candidates.length; i++) {
    const value = data[candidates[i]];
    if (value !== undefined && value !== null && value !== '') {
      return value;
    }
  }
  return null;
}

/**
 * 应用「体参数 → 路径参数」改写。
 *
 * @returns {{path: string, data: object}} 改写后的路径与请求体
 */
function resolveActionAlias(path, method, data) {
  if (!path) {
    return { path, data };
  }
  for (let i = 0; i < ACTION_ALIAS.length; i++) {
    const rule = ACTION_ALIAS[i];
    if (rule.from !== path) {
      continue;
    }
    if (rule.method && rule.method !== method) {
      continue;
    }
    const orderKey = extractOrderKey(data);
    if (!orderKey) {
      // 找不到订单标识就无法改写。原样发出让后端报 400/404，
      // 日志里能直接看到是哪个页面漏传了参数 ——
      // 比在这里静默吞掉、让页面收到一个语焉不详的失败要好排查得多
      console.warn('[cloud-shim] 该接口需要订单标识，但请求体里没有 |', path, data);
      return { path, data };
    }
    // 改写后把订单标识从请求体里摘掉：新接口从路径上取，
    // 留着虽然无害（@RequestBody 会忽略未知字段），
    // 但会让人误以为"后端既读路径又读 body"，给排查添乱
    const rest = Object.assign({}, data);
    delete rest.order_id;
    delete rest.orderId;
    delete rest.order_no;
    delete rest.orderNo;
    delete rest.id;
    return { path: rule.to.replace('{id}', orderKey), data: rest };
  }
  return { path, data };
}

/**
 * 解析路径别名。
 * @returns {{path: string, method: string}} 解析后的路径与方法
 */
function resolveAlias(path, method) {
  if (!path) {
    return { path, method };
  }

  const exact = PATH_ALIAS[path];
  if (exact) {
    if (typeof exact === 'string') {
      return { path: exact, method };
    }
    return { path: exact.path, method: exact.method || method };
  }

  for (let i = 0; i < PATH_PREFIX_ALIAS.length; i++) {
    const rule = PATH_PREFIX_ALIAS[i];
    if (path.indexOf(rule.from) === 0) {
      return {
        path: rule.to + path.substring(rule.from.length),
        method: rule.method || method
      };
    }
  }

  // 没有映射规则 → 原样返回。后端会返回 404，日志里能看到是哪个接口还没实现
  return { path, method };
}

// ============================================================================
// 便捷方法
// ============================================================================
const get = (url, data, opts) => request(Object.assign({ url, method: 'GET', data }, opts));
const post = (url, data, opts) => request(Object.assign({ url, method: 'POST', data }, opts));
const put = (url, data, opts) => request(Object.assign({ url, method: 'PUT', data }, opts));
const del = (url, data, opts) => request(Object.assign({ url, method: 'DELETE', data }, opts));

// ============================================================================
// wx.cloud.callContainer 兼容垫片
// ============================================================================
/**
 * 安装垫片：把旧页面的 callContainer 调用桥接到 wx.request。
 *
 * 只在 app.js 的 onLaunch 里调用一次。调用后，页面里原有的
 * `wx.cloud.callContainer({...})` 会无缝转发到新后端。
 *
 * 【响应形态的处理】
 * 旧页面读的是 res.data.xxx（旧 Node 后端直接返回业务对象），
 * 新后端返回的是 Result<T>，业务数据在 body.data 里。
 * 垫片负责把 body.data 提取出来放到 res.data，让页面无需感知这次变化。
 */
function installCloudShim() {
  if (typeof wx === 'undefined') {
    return;
  }
  if (!wx.cloud) {
    wx.cloud = {};
  }

  /*
   * 【安装前先把原生实现存起来 —— 顺序绝不能反】
   *
   * 下面几行就把 wx.cloud.callContainer 覆盖成垫片了。
   * 而云托管模式下 transport() 必须调用**原生**实现，
   * 所以必须先备份，否则就是"垫片调用自己"的无限递归。
   *
   * 用 __nqxShimInstalled 打标记：万一 installCloudShim 被调用两次，
   * 第二次的 wx.cloud.callContainer 已经是垫片了，不能再当成原生实现存下来
   * ——否则又把自己存成"原生"，递归依旧。
   */
  if (typeof wx.cloud.callContainer === 'function' && !wx.cloud.callContainer.__nqxShimInstalled) {
    nativeCallContainer = wx.cloud.callContainer;
  }

  const shim = function (opts) {
    const {
      path,
      method = 'GET',
      data,
      header,
      success,
      fail,
      complete
    } = opts || {};

    // 旧路径 → 新路径。这一步加上之后，页面里的 path 一个字都不用改
    const originalPath = path;

    /*
     * 【两步转换的顺序很重要，必须「先搬参数、后换路径」】
     *
     * 旧版的 pay / cancel / receive 是完整路径：
     *     POST /api/procurement/orders/pay   { order_id: 123 }
     * 而订单详情是「前缀 + 主键」：
     *     GET  /api/procurement/orders/12
     *
     * 为了让详情也能映射，PATH_PREFIX_ALIAS 里必须有 '/api/procurement/orders/' 这条前缀。
     * 但前缀映射是机械替换，它认不出 pay 是「动作」而不是「主键」——
     * 若先做前缀映射，/api/procurement/orders/pay 会先被改写成
     * /app/order/pay，ACTION_ALIAS 再也匹配不上（它的 from 是旧路径），
     * 结果就是把订单主键当成了字面量 "pay"，请求打到 /app/order/pay 上。
     *
     * 所以：ACTION_ALIAS（体参数 → 路径参数）必须先跑，它匹配的是**原路径**；
     * 命中之后就不再走 PATH_ALIAS/PATH_PREFIX_ALIAS ——
     * ACTION_ALIAS 的 to 已经是最终的新路径，再映射一次是多余且有风险的。
     * 没命中时才交给 resolveAlias 做静态路径替换。
     */
    const action = resolveActionAlias(originalPath, method, data);
    const actionApplied = action.path !== originalPath;
    const resolved = actionApplied
      ? { path: action.path, method: method }
      : resolveAlias(originalPath, method);

    request({ url: resolved.path, method: resolved.method, data: action.data, header })
      .then((payload) => {
        // 先把 /files/ 相对路径补成绝对地址（小程序里 <image> 必须走完整 URL），
        // 再按原路径还原旧返回结构（见 DATA_WRAPPED_PATHS 与 isDataWrapped 的说明）
        const withAssets = rewriteAssetUrls(payload);
        const body = isDataWrapped(originalPath)
          ? { data: unwrapPagePayload(withAssets) }
          : withAssets;
        // 统一成旧页面期望的形态：{ statusCode, data }
        if (typeof success === 'function') {
          success({ statusCode: 200, data: body });
        }
      })
      .catch((err) => {
        if (typeof fail === 'function') {
          fail(err);
        }
      })
      .then(() => {
        /*
         * complete 回调必须触发。
         *
         * wx.request 会在成功或失败后都调用 complete，旧页面用它来
         * hideLoading 与复位下拉刷新状态。原实现在这里漏掉了，
         * 结果是：请求失败了 loading 转圈一直不消失、下拉刷新的
         * 小菊花也收不回去 —— 用户以为卡死了。
         *
         * 放在链尾的 .then 而不是 finally，是因为 Promise.prototype.finally
         * 在小程序基础库的兼容性不如 then 稳妥；这里要兼容旧基础库。
         */
        if (typeof complete === 'function') {
          complete();
        }
      });
  };

  // 打标记 + 挂上去。标记用于防止重复安装时把垫片误当成原生实现备份。
  shim.__nqxShimInstalled = true;
  wx.cloud.callContainer = shim;

  if (env.isDebug) {
    console.log('[cloud-shim] 已接管 wx.cloud.callContainer',
      env.USE_CALL_CONTAINER ? '（传输：原生 callContainer）' : '（传输：wx.request → ' + env.API_BASE_URL + '）');
  }
}

module.exports = {
  request,
  get,
  post,
  put,
  del,
  BizError,
  installCloudShim,
  silentReLogin,
  buildUrl,
  resolveAlias,
  // 导出包装判断：供 scripts/check-alias-coverage.js 在 Node 下核对
  // 「哪些旧路径会被还原成 { data: ... }」，页面运行时不需要用到它
  isDataWrapped,
  PATH_ALIAS,
  CODE_SUCCESS,
  CODE_UNAUTHORIZED
};
