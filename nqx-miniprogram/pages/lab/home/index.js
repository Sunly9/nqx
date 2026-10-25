const app = getApp();

Page({
  data: {
    userInfo: null,
    stats: {
      totalWeight: 0, // 累计回收重量 (kg)
      carbonReduction: 0, // 碳减排量 (kg)
      totalPoints: 0 // 当前积分
    },
    latestOrder: null, // 最新订单状态
    showCertificate: false, // 是否显示证书
    tipContent: '废旧电池中含有汞、镉、铅等重金属，如果随意丢弃，会污染土壤和水源。一颗纽扣电池可污染60万升水，相当于一个人一生的用水量。', // 默认每日一识
    announcement: null // 公告数据
  },

  onShow() {
    if (typeof this.getTabBar === 'function' &&
      this.getTabBar()) {
      this.getTabBar().setData({
        selected: 0
      })
    }
    
    // 从全局数据更新用户信息和统计数据
    const userInfo = app.globalData.userInfo;
    if (userInfo) {
      this.setData({
        userInfo: userInfo,
        stats: {
          totalWeight: parseFloat(userInfo.total_weight || 0).toFixed(2),
          carbonReduction: parseFloat(userInfo.carbon_saved || 0).toFixed(2),
          totalPoints: parseInt(userInfo.points || 0)
        }
      });
      // 每次显示页面都尝试获取最新订单状态
      this.fetchLatestOrder(userInfo.id);
    }
    
    // 每次显示页面时，尝试刷新每日一识（或者你可以放在 onLoad 里只刷新一次）
    this.fetchRandomTip();
    // 获取最新公告
    this.fetchAnnouncement();
  },

  onLoad() {
    const userInfo = app.globalData.userInfo;
    if (userInfo) {
      this.setData({
        userInfo: userInfo,
        stats: {
          totalWeight: parseFloat(userInfo.total_weight || 0).toFixed(2),
          carbonReduction: parseFloat(userInfo.carbon_saved || 0).toFixed(2),
          totalPoints: parseInt(userInfo.points || 0)
        }
      });
    }
    // 首次加载获取一次
    this.fetchRandomTip();
    this.fetchAnnouncement();
  },

  fetchAnnouncement() {
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/announcements/active',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode === 200 && res.data.data) {
          this.setData({
            announcement: res.data.data
          });
        } else {
          this.setData({ announcement: null });
        }
      }
    });
  },

  fetchRandomTip() {
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/tips/random',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode === 200 && res.data.data) {
          this.setData({
            tipContent: res.data.data.content
          });
        }
      }
    });
  },

  fetchLatestOrder(userId) {
    // 获取订单列表，取第一个未完成的订单
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/orders/list',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userId },
      success: (res) => {
        if (res.statusCode === 200 && res.data.data && res.data.data.length > 0) {
          const activeOrder = res.data.data.find(o => o.status === 'pending' || o.status === 'processing');
          if (activeOrder) {
            this.setData({ latestOrder: this.buildOrderStatus(activeOrder) });
          } else {
            this.setData({ latestOrder: null });
          }
        }
      }
    });
  },

  /**
   * 用**真实订单状态**组装首页的"当前进度"卡片。
   *
   * <h3>⚠️ 这里原来返回的是编造的数据</h3>
   *
   * 原实现叫 `generateMockStatus`，无论订单实际处于哪一步，都返回固定的假文案：
   *
   * <pre>
   *   上门单 → "调度中心：已指派回收员 [王师傅] 接单，距您 4.2km，预计 18分钟到达。"
   *   寄送单 → "物流更新：您的包裹已到达 [顺义分拣中心]，正在发往 [天津处理厂]。"
   *   时间   → "刚刚" / "10分钟前"
   * </pre>
   *
   * 系统里既没有"王师傅"，也没有"顺义分拣中心"，更没有距离计算 ——
   * 这些**全部是写死的字符串**。问题不在于"演示时好看"，而在于
   * 把虚构的进度显示在用户首页，让用户以为回收员已经出发了。
   *
   * 这类"编造的状态"比不显示更糟：**它会掩盖真实问题**。
   * 订单其实一直卡在待审核，假文案会让用户和开发都以为流程在推进。
   *
   * <p>现在直接展示后端下发的真实状态：标题按回收方式区分，
   * 描述用 {@code statusText}（后端已翻译好的中文），时间用真实下单时间。
   */
  buildOrderStatus(order) {
    const isPickup = order.type === 'pickup';
    return {
      type: order.type,
      title: isPickup ? '上门回收进度' : '寄送处理进度',
      desc: order.statusText || '订单处理中',
      time: order.created_at ? this.formatTime(order.created_at) : ''
    };
  },

  /** 把 ISO 时间格式化成 MM-DD HH:mm，仅用于卡片上的时间戳 */
  formatTime(value) {
    const d = new Date(value);
    if (isNaN(d.getTime())) {
      return '';
    }
    const pad = (n) => (n < 10 ? '0' + n : '' + n);
    return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes());
  },

  navTo(e) {
    const url = e.currentTarget.dataset.url;
    if (url) {
      wx.navigateTo({
        url
      });
    }
  },

  showCert() {
    this.setData({ showCertificate: true });
  },

  hideCert() {
    this.setData({ showCertificate: false });
  },

  // 阻止冒泡
  noop() {}
})
