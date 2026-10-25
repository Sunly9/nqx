/**
 * 环境配置
 * ============================================================================
 * 本项目支持两种「连后端」的方式，由下面的 ENV 切换：
 *
 *   LOCAL / LAN / PROD  →  用 wx.request 直连一个 HTTP(S) 地址。
 *                          需要在微信后台配「服务器域名」，且域名要 HTTPS + 备案。
 *
 *   CLOUDRUN            →  用原生 wx.cloud.callContainer 走微信云托管。
 *                          优点：走内网、**不需要配服务器域名**、免 DDoS，
 *                                后端还能直接从请求头拿 openid（省掉 jscode2session）。
 *
 * 为什么默认是 LOCAL：本地开发不该因为没配云环境就跑不起来。
 * 部署到云托管后，把 ENV 改成 'CLOUDRUN' 并填好下面两个 ID 即可 —— 页面代码一行都不用改。
 * ============================================================================
 */

// ---------------------------------------------------------------------------
// 微信云托管（ENV = 'CLOUDRUN' 时生效）
// ---------------------------------------------------------------------------
/**
 * 云托管环境 ID。
 *
 * 在哪看：微信云托管控制台 → 环境 → 环境 ID（形如 `your-cloudrun-env-id`）。
 * ⚠️ 必须换成你自己的，否则 callContainer 会报「环境不存在」。
 */
const CLOUDRUN_ENV_ID = 'your-cloudrun-env-id';

/**
 * 云托管服务名。
 *
 * 在哪看：云托管控制台 → 服务管理 → 服务列表 → 服务名称。
 * 一个环境里可以有多个服务（如 app、admin），这里指后端那个。
 */
const CLOUDRUN_SERVICE = 'app';

// ---------------------------------------------------------------------------
// 直连模式（ENV = LOCAL / LAN / PROD 时生效）
// ---------------------------------------------------------------------------

// 本机后端（微信开发者工具 + 勾选"不校验合法域名"时可用）
const LOCAL_API_URL = 'http://127.0.0.1:8080';

// 真机调试：把 192.168.x.x 换成你电脑的局域网 IP（手机与电脑需在同一 WiFi）
// ⚠️ 微信要求真机走 HTTPS，所以真机直连本机通常仍需内网穿透
const LAN_API_URL = 'http://192.168.1.100:8080';

// 生产环境：备案过的 HTTPS 域名（自建服务器时用）
const PROD_API_URL = 'https://your-cloud-domain.com';

/**
 * 云托管服务的**公网域名**。
 *
 * 在哪看：云托管控制台 → 服务 app → 服务设置 → 公网访问。
 *
 * 为什么云托管模式还需要它：
 *
 *   接口请求走 `callContainer`（内网，不需要域名）；
 *   但 **图片上传走 `wx.uploadFile`**，这个 API 不支持 callContainer，
 *   必须打一个真实的 HTTPS 地址。所以这里要填公网域名。
 *
 * ⚠️ 真机上 `wx.uploadFile` 仍然要求该域名在小程序后台的
 *    「uploadFile 合法域名」里。本地调试可以在开发者工具里勾选
 *    「不校验合法域名」绕过；真机预览/发布必须去后台配。
 */
const CLOUDRUN_PUBLIC_URL = 'https://your-cloudrun-domain';

/**
 * 当前使用哪个环境。
 *
 *   'LOCAL'    → 微信开发者工具模拟器，直连 127.0.0.1:8080
 *   'LAN'      → 真机 + 局域网 IP（并把 LAN_API_URL 换成自己的 IP）
 *   'PROD'     → 自建服务器，直连备案域名
 *   'CLOUDRUN' → 微信云托管（上生产推荐这个）
 */
const ENV = 'LOCAL';

const URL_MAP = {
  LOCAL: LOCAL_API_URL,
  LAN: LAN_API_URL,
  PROD: PROD_API_URL,
  CLOUDRUN: CLOUDRUN_PUBLIC_URL
};

/**
 * 后端基地址。
 *
 * 云托管模式下接口请求走 callContainer 的 path（不用这个值），
 * 但 `wx.uploadFile`（图片上传）只能打真实地址，所以它仍然必须是个可用的 HTTPS 域名。
 *
 * 早期版本的 bug：URL_MAP 里没有 CLOUDRUN 这一项，于是这里退化成
 * `LOCAL_API_URL` = http://127.0.0.1:8080 —— 在手机上 127.0.0.1 指的是手机自己，
 * 上传必然失败，而且报错只说"网络错误"，完全看不出原因。
 */
const API_BASE_URL = URL_MAP[ENV] || LOCAL_API_URL;

/**
 * 是否使用原生 callContainer。
 *
 * 垫片（utils/request.js）和 app.js 都读这个开关 ——
 * 有了它，「本地直连」和「云托管内网」就能共用同一套页面代码，
 * 只在最底层换传输实现。
 */
const USE_CALL_CONTAINER = ENV === 'CLOUDRUN';

module.exports = {
  ENV,
  API_BASE_URL,
  USE_CALL_CONTAINER,
  CLOUDRUN_ENV_ID,
  CLOUDRUN_SERVICE,
  // 便于排查：在控制台打印当前环境
  isDebug: ENV !== 'PROD'
};
