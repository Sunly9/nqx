const app = getApp();

Page({
  data: {
    tabs: [
      { id: 'all', name: '全部' },
      { id: 'pending_payment', name: '待付款' },
      { id: 'pending_shipment', name: '待发货' },
      { id: 'pending_receipt', name: '待收货' },
      { id: 'pending_evaluation', name: '待评价' },
      { id: 'refund', name: '退款/售后' },
      { id: 'completed', name: '已完成' }
    ],
    currentTab: 'all',
    orders: [],
    loading: false
  },

  onLoad(options) {
    if (options.status) {
      this.setData({
        currentTab: options.status
      });
    }
  },

  onShow() {
    this.fetchOrders();
  },

  onPullDownRefresh() {
    this.fetchOrders(() => {
      wx.stopPullDownRefresh();
    });
  },

  switchTab(e) {
    const status = e.currentTarget.dataset.id;
    this.setData({
      currentTab: status
    });
    this.fetchOrders();
  },

  fetchOrders(cb) {
    const userInfo = app.globalData.userInfo;
    if (!userInfo || !userInfo.id) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    this.setData({ loading: true });

    // 如果是“退款/售后”标签，获取维修列表
    if (this.data.currentTab === 'refund') {
      wx.cloud.callContainer({
        env: 'your-cloudrun-env-id',
        service: 'app',
        path: '/api/procurement/repair/list',
        method: 'GET',
        header: { 'content-type': 'application/json' },
        data: { user_id: userInfo.id },
        success: (res) => {
          if (res.statusCode === 200) {
            const list = res.data.data.map(item => ({
              ...item,
              isRepair: true,
              statusText: this.getRepairStatusText(item.status),
              formattedDate: new Date(item.created_at).toLocaleString()
            }));
            this.setData({ orders: list, loading: false });
          }
        },
        complete: () => {
          if (cb) cb();
          this.setData({ loading: false });
        }
      });
      return;
    }

    // 读取已关闭气泡的订单ID列表
    const dismissedIds = wx.getStorageSync('reviewBubbleDismissed') || [];
    const dismissedSet = new Set(dismissedIds);

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/procurement/orders/list',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: {
        user_id: userInfo.id,
        status: this.data.currentTab,
        /*
         * ⚠️ 必须显式传 orderType=2（采购单）。
         *
         * 原来这里只传了 status，而 /app/order/page 在不传 orderType 时
         * **返回该用户的全部订单（回收单 + 采购单）**。
         * 于是「我的采购订单」里会混进回收单 —— 而且因为回收单的
         * status 也被映射成 'completed'/'cancelled' 这些共用词，
         * 按 tab 筛选时同样筛不掉。
         */
        orderType: 2
      },
      success: (res) => {
        if (res.statusCode === 200) {
          const list = res.data.data.map(item => {
            const bubbleDismissed = dismissedSet.has(item.id);
            return {
              ...item,
              bubbleDismissed,
              statusText: this.getStatusText(item.status),
              formattedDate: new Date(item.created_at).toLocaleString(),
              /*
               * 「提醒发货」按钮的文案。
               *
               * 点过之后不能再显示"提醒发货"——否则用户会以为没点上，
               * 反复点、反复被限频拦截，体验极差。
               *
               * 这里不自己算"能不能提醒"，直接用后端下发的 can_remind_ship：
               * 24 小时限频规则只应该有一份实现，放前端既会改漏、
               * 也能被用户改本地时间绕过。
               */
              remindText: this.buildRemindText(item)
            };
          });
          
          this.setData({
            orders: list,
            loading: false
          });
        }
      },
      complete: () => {
        if (cb) cb();
        this.setData({ loading: false });
      }
    });
  },

  navToDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/procurement/orders/detail/index?id=${id}`
    });
  },

  getStatusText(status) {
    const map = {
      'pending_payment': '待付款',
      'pending_shipment': '待发货',
      'pending_receipt': '待收货',
      'pending_evaluation': '待评价',
      'completed': '已完成',
      'refund': '退款中',
      'cancelled': '已取消'
    };
    return map[status] || status;
  },

  /*
   * 维修状态文案。
   *
   * ⚠️ 旧后端的状态是字符串（pending/processing/completed/rejected），
   * 新后端改成了整数（1待处理 2处理中 3已完成 4已驳回）并在 VO 里
   * 额外提供了 status_text。这里两套键都保留：
   *   · 数字键适配新接口；
   *   · 字符串键保留，万一某处数据还是旧格式也不会显示成空白。
   *
   * JS 对象的数字键在底层就是字符串键，所以 map[1] 与 map['1'] 等价，
   * 写成数字只是更直观。
   */
  getRepairStatusText(status) {
    const map = {
      1: '⏳ 等待商家审核',
      2: '🔧 商家已接单，维修中',
      3: '✅ 维修服务已完成',
      4: '❌ 申请被拒绝',
      // —— 兼容旧后端的字符串取值 ——
      'pending': '⏳ 等待商家审核',
      'processing': '🔧 商家已接单，维修中',
      'completed': '✅ 维修服务已完成',
      'rejected': '❌ 申请被拒绝'
    };
    return map[status] || status;
  },

  // 支付
  handlePay(e) {
    const orderId = e.currentTarget.dataset.id;
    const amount = e.currentTarget.dataset.amount;
    const userInfo = app.globalData.userInfo;

    wx.showModal({
      title: '确认支付',
      content: `确认支付 ¥${amount} 吗？`,
      confirmText: '支付',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '支付中...' });
          
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: '/api/procurement/orders/pay',
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: {
              order_id: orderId,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '支付成功', icon: 'success' });
                this.fetchOrders();
              } else {
                wx.showToast({ title: '支付失败', icon: 'none' });
              }
            }
          });
        }
      }
    });
  },

  // 取消订单
  handleCancel(e) {
    const orderId = e.currentTarget.dataset.id;
    const userInfo = app.globalData.userInfo;

    wx.showModal({
      title: '取消订单',
      content: '确定要取消该订单吗？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' });
          
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: '/api/procurement/orders/cancel',
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: {
              order_id: orderId,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '订单已取消', icon: 'success' });
                this.fetchOrders();
              } else {
                wx.showToast({ 
                  title: apiRes.data.message || '取消失败', 
                  icon: 'none' 
                });
              }
            }
          });
        }
      }
    });
  },

  /*
   * 生成「提醒发货」按钮的文案。
   *
   *   · 还能提醒  → '提醒发货'
   *   · 刚提醒过  → '已提醒 16:30'（同一天只显示时分，跨天显示月-日 时:分）
   *
   * 跨天的情况也要处理：24 小时限频下，"昨天 23:50 提醒过" 到今天
   * 下午仍然是不可提醒状态，只显示 "23:50" 会让人误以为是今天。
   */
  buildRemindText(item) {
    if (item.can_remind_ship) {
      return '提醒发货';
    }
    const raw = item.remind_ship_at;
    if (!raw) {
      // 理论上不该出现（不能提醒且没有提醒时间），兜底成已提醒
      return '已提醒';
    }
    const t = new Date(String(raw).replace(' ', 'T'));
    if (isNaN(t.getTime())) {
      return '已提醒';
    }
    const pad = (n) => (n < 10 ? '0' + n : '' + n);
    const now = new Date();
    const sameDay = t.getFullYear() === now.getFullYear()
      && t.getMonth() === now.getMonth()
      && t.getDate() === now.getDate();
    const hhmm = pad(t.getHours()) + ':' + pad(t.getMinutes());
    return sameDay
      ? '已提醒 ' + hhmm
      : '已提醒 ' + (t.getMonth() + 1) + '-' + t.getDate() + ' ' + hhmm;
  },

  /*
   * 提醒发货。
   *
   * 【这已经不是原来那个"假按钮"了】
   *
   * 旧实现只弹一段静态的「发货说明」，不给后端发任何请求，管理员完全不知道。
   * 现在真的调 POST /app/order/{id}/remind-ship：
   *   · 后端把 remind_ship_at / remind_ship_count 写进库；
   *   · 管理后台的采购订单列表能看到"被催 N 次"；
   *   · 同一订单 24 小时内只能提醒一次，超了会返回明确的业务错误。
   *
   * 注意：这**不是发货**。发货仍然只能管理员操作（带权限码的接口），
   * 买家点这个按钮改不了任何订单状态 —— 只是把"我等着呢"这个信号传过去。
   */
  handleRemindShip(e) {
    const orderId = e && e.currentTarget && e.currentTarget.dataset
      ? e.currentTarget.dataset.id
      : null;
    const canRemind = e && e.currentTarget && e.currentTarget.dataset
      ? e.currentTarget.dataset.can
      : true;

    if (!orderId) {
      return;
    }
    if (canRemind === false || canRemind === 'false') {
      wx.showToast({ title: '今天已经提醒过了', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '发送中…', mask: true });

    wx.cloud.callContainer({
      config: { env: 'your-cloudrun-env-id' },
      path: '/app/order/' + orderId + '/remind-ship',
      method: 'POST',
      header: { 'X-WX-SERVICE': 'app', 'content-type': 'application/json' },
      data: {},
      success: (res) => {
        const body = res && res.data;
        if (res.statusCode === 200 && body && body.code === 200) {
          const updated = body.data || {};
          // 就地更新这一条，避免整页重新拉（列表会闪一下）
          const orders = (this.data.orders || []).map((o) => {
            if (o.id !== orderId) {
              return o;
            }
            const merged = Object.assign({}, o, updated, {
              // 后端返回的是 snake_case，页面模板用的也是 snake_case，直接透传
              can_remind_ship: updated.can_remind_ship,
              remind_ship_at: updated.remind_ship_at
            });
            merged.remindText = this.buildRemindText(merged);
            return merged;
          });
          this.setData({ orders });
          wx.showToast({ title: '已提醒商家发货', icon: 'success' });
          return;
        }
        // 业务错误（比如 24 小时内重复提醒）如实告诉用户
        const msg = (body && (body.msg || body.message)) || '提醒失败，请稍后再试';
        wx.showToast({ title: msg, icon: 'none' });
      },
      fail: () => {
        wx.showToast({ title: '网络异常，请稍后再试', icon: 'none' });
      },
      complete: () => {
        wx.hideLoading();
      }
    });
  },

  // 确认收货
  handleReceive(e) {
    const orderId = e.currentTarget.dataset.id;
    const userInfo = app.globalData.userInfo;

    wx.showModal({
      title: '确认收货',
      content: '确认已收到商品且无误吗？',
      success: (res) => {
        if (res.confirm) {
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: '/api/procurement/orders/receive',
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: {
              order_id: orderId,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '交易完成', icon: 'success' });
                this.fetchOrders();
              }
            }
          });
        }
      }
    });
  },

  // 去评价
  handleReview(e) {
    const orderId = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/procurement/orders/review/index?order_id=${orderId}`
    });
  },

  // 关闭评价气泡
  handleDismissBubble(e) {
    const orderId = e.currentTarget.dataset.id;
    const dismissedIds = wx.getStorageSync('reviewBubbleDismissed') || [];
    if (!dismissedIds.includes(orderId)) {
      dismissedIds.push(orderId);
      wx.setStorageSync('reviewBubbleDismissed', dismissedIds);
    }
    const updated = this.data.orders.map(item => {
      if (item.id === orderId) {
        return { ...item, bubbleDismissed: true };
      }
      return item;
    });
    this.setData({ orders: updated });
  },

  // 申请维修
  handleRepair(e) {
    const orderId = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/procurement/repair/apply/index?order_id=${orderId}`
    });
  }
})
