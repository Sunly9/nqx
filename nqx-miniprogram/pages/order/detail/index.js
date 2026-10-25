const app = getApp();

Page({
  data: {
    order: null,
    loading: true
  },

  onLoad(options) {
    if (options.id) {
      this.fetchOrderDetail(options.id);
    } else {
      wx.showToast({ title: '参数错误', icon: 'none' });
      setTimeout(() => wx.navigateBack(), 1500);
    }
  },

  fetchOrderDetail(id) {
    wx.showLoading({ title: '加载中' });
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: `/api/orders/${id}`,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200 && res.data.data) {
          const order = res.data.data;
          order.statusText = this.getStatusText(order.status);
          order.typeText = order.type === 'pickup' ? '上门回收' : '寄送处理';
          if (order.created_at) {
            order.created_at = new Date(order.created_at).toLocaleString();
          }
          if (order.pickup_time) {
            order.pickup_time = new Date(order.pickup_time).toLocaleString();
          }
          this.setData({ order, loading: false });
        } else {
          wx.showToast({ title: '获取详情失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  },

  getStatusText(status) {
    const map = {
      'pending': '待处理',
      'processing': '进行中',
      'completed': '已完成',
      'cancelled': '已取消'
    };
    return map[status] || status;
  }
})
