const app = getApp();

Page({
  data: {
    order: null,
    loading: true
  },

  onLoad(options) {
    if (options.id) {
      this.fetchOrderDetail(options.id);
    }
  },

  onPullDownRefresh() {
    if (this.data.order) {
      this.fetchOrderDetail(this.data.order.id, () => {
        wx.stopPullDownRefresh();
      });
    }
  },

  fetchOrderDetail(id, cb) {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    this.setData({ loading: true });

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: `/api/procurement/orders/${id}`,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        if (res.statusCode === 200) {
          const order = res.data.data;
          order.formattedDate = new Date(order.created_at).toLocaleString();
          order.statusText = this.getStatusText(order.status);
          order.statusDesc = this.getStatusDesc(order.status);
          order.remindText = this.buildRemindText(order);
          this.setData({ order, loading: false });
        } else {
            wx.showToast({ title: '订单不存在', icon: 'none' });
        }
      },
      fail: () => {
          wx.showToast({ title: '网络错误', icon: 'none' });
      },
      complete: () => {
        if (cb) cb();
        this.setData({ loading: false });
      }
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

  getStatusDesc(status) {
      const map = {
          'pending_payment': '请尽快完成支付，超时将自动取消',
          'pending_shipment': '商家正在打包商品，请耐心等待',
          'pending_receipt': '商品已发出，请留意快递信息',
          'completed': '交易已完成，感谢您的购买',
          'cancelled': '订单已取消'
      };
      return map[status] || '';
  },

  // 支付
  handlePay() {
    const order = this.data.order;
    const userInfo = app.globalData.userInfo;

    wx.showModal({
      title: '确认支付',
      content: `确认支付 ¥${order.total_amount} 吗？`,
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
              order_id: order.id,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '支付成功', icon: 'success' });
                this.fetchOrderDetail(order.id);
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
  handleCancel() {
    const order = this.data.order;
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
              order_id: order.id,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '订单已取消', icon: 'success' });
                this.fetchOrderDetail(order.id);
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
   * 「提醒发货」按钮文案。
   *
   * 与列表页 pages/procurement/orders/index.js 的 buildRemindText 保持一致；
   * 两处都没有自己算"能不能提醒"，统一用后端下发的 can_remind_ship，
   * 避免限频规则出现第二份实现。
   */
  buildRemindText(order) {
    if (!order || order.can_remind_ship) {
      return '提醒发货';
    }
    const raw = order.remind_ship_at;
    if (!raw) {
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
   * 提醒发货（真调用，不再是原来的静态说明弹窗）。
   *
   * 详见列表页 pages/procurement/orders/index.js 中 handleRemindShip 的说明：
   * 这个按钮只会把"买家在催"写进订单，改不了订单状态 ——
   * 发货仍然只能管理员操作。
   */
  handleRemindShip() {
    const order = this.data.order;
    if (!order || !order.id) {
      return;
    }
    if (order.can_remind_ship === false) {
      wx.showToast({ title: '今天已经提醒过了', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '发送中…', mask: true });

    wx.cloud.callContainer({
      config: { env: 'your-cloudrun-env-id' },
      path: '/app/order/' + order.id + '/remind-ship',
      method: 'POST',
      header: { 'X-WX-SERVICE': 'app', 'content-type': 'application/json' },
      data: {},
      success: (res) => {
        const body = res && res.data;
        if (res.statusCode === 200 && body && body.code === 200) {
          const updated = body.data || {};
          const merged = Object.assign({}, order, {
            can_remind_ship: updated.can_remind_ship,
            remind_ship_at: updated.remind_ship_at
          });
          merged.remindText = this.buildRemindText(merged);
          this.setData({ order: merged });
          wx.showToast({ title: '已提醒商家发货', icon: 'success' });
          return;
        }
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
  handleReceive() {
    const order = this.data.order;
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
              order_id: order.id,
              user_id: userInfo.id
            },
            success: (apiRes) => {
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '交易完成', icon: 'success' });
                this.fetchOrderDetail(order.id);
              }
            }
          });
        }
      }
    });
  }
})
