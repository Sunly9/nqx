/**
 * 小程序全局逻辑
 * ============================================================================
 *   - 启动时按需初始化云托管（CLOUDRUN 模式）
 *   - 安装 callContainer 兼容垫片，让旧页面零改动地打到新后端
 *   - 保留原有 globalData 结构，避免影响已有页面
 * ============================================================================
 */

const request = require('./utils/request');
const env = require('./utils/env');

App({

  onLaunch() {
    // ------------------------------------------------------------------
    // 云托管模式：必须先 wx.cloud.init()，否则 callContainer 会报
    // 「Cloud API isn't enabled」。
    //
    // 这一步在**直连模式**下不做 —— 本地开发不该依赖云环境。
    // ------------------------------------------------------------------
    if (env.USE_CALL_CONTAINER) {
      try {
        wx.cloud.init({ env: env.CLOUDRUN_ENV_ID, traceUser: true });
        console.log('[app] 云托管模式 | env=' + env.CLOUDRUN_ENV_ID
          + ' | service=' + env.CLOUDRUN_SERVICE);
      } catch (e) {
        // 初始化失败不阻塞启动：后面每次 callContainer 会各自报错，
        // 比在这里直接崩掉更容易定位
        console.error('[app] wx.cloud.init 失败，请求可能全部失败', e);
      }
    }

    // ------------------------------------------------------------------
    // 关键一步：接管 wx.cloud.callContainer。
    // 必须在任何页面发请求之前调用，所以放在 onLaunch 的最前面。
    //
    // 注意：垫片只负责「路径映射 / 解包 / 401 重登」这些业务逻辑，
    // 真正的传输由 request.js 里的 transport() 决定走 wx.request 还是原生 callContainer。
    // ------------------------------------------------------------------
    request.installCloudShim();

    // 恢复本地登录态
    const userInfo = wx.getStorageSync('userInfo');
    const role = wx.getStorageSync('role');
    if (userInfo) {
      this.globalData.userInfo = userInfo;
    }
    if (role) {
      this.globalData.role = role;
    }

    // 恢复购物车（旧版存在本地 Storage，不依赖后端）
    const cart = wx.getStorageSync('cart');
    if (cart) {
      this.globalData.cart = cart;
    }

    // ------------------------------------------------------------------
    // 静默刷新用户信息。
    //
    // 这里刻意**不做登录态校验**：若直接调 /app/user/info 而 token 已失效，
    // request 层会触发静默重登，用户体验上没有任何感知。
    // 失败也不弹提示 —— 启动阶段的失败不该打断用户进入首页。
    // ------------------------------------------------------------------
    this.refreshUserInfo();
  },

  /**
   * 刷新用户信息。
   *
   * 与旧版不同：不再需要区分 lab / procurement 两个接口 ——
   * 新后端只有一个 app_user 表，一个用户可以同时是回收方和采购方，
   * 身份体现在返回的 roles 数组里。
   */
  refreshUserInfo(callback) {
    const cached = this.globalData.userInfo || wx.getStorageSync('userInfo');
    if (!cached || !cached.id) {
      if (callback) callback(null);
      return;
    }

    request.get('/app/auth/info', null, { silent: true })
      .then((data) => {
        const userInfo = (data && data.userInfo) || data;
        if (userInfo) {
          this.globalData.userInfo = userInfo;
          wx.setStorageSync('userInfo', userInfo);
        }
        if (callback) callback(userInfo);
      })
      .catch((err) => {
        // 静默失败：可能只是网络不通，下次进页面还会重试
        console.warn('[app] 刷新用户信息失败:', err && err.message);
        if (callback) callback(null);
      });
  },

  globalData: {
    userInfo: null,
    /** 'lab' 回收方 | 'procurement' 采购方 —— 仅用于前端路由分流，后端以 roles 数组为准 */
    role: null,
    cart: [],
    brandLogoUrl: ''
  }
});
