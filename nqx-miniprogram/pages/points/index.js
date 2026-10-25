const app = getApp();

Page({
  data: {
    totalPoints: 0,
    showHistory: false, // 控制是否显示明细弹窗
    goodsList: [], // Now fetched from backend
    history: []
  },

  onLoad(options) {
    this.updatePoints();
    this.fetchGoods();

    // 如果参数要求显示历史记录，则自动打开
    if (options.showHistory) {
      this.setData({ showHistory: true });
      this.fetchHistory();
    }
  },

  onShow() {
    this.updatePoints();
    // 每次显示页面刷新商品列表，以获取最新库存
    this.fetchGoods();
  },

  updatePoints() {
    const userInfo = app.globalData.userInfo;
    if (userInfo && userInfo.id) {
      // 优先显示缓存数据，避免空白
      this.setData({
        totalPoints: parseInt(userInfo.points || 0)
      });
      
      // 主动从服务器获取最新积分
      this.fetchLatestUserInfo(userInfo.id);
    }
  },

  fetchLatestUserInfo(userId) {
    const role = app.globalData.role || wx.getStorageSync('role');
    
    const path = role === 'procurement' ? '/api/procurement/user/info' : '/api/user/info';

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userId },
      success: (res) => {
        if (res.statusCode === 200 && res.data.userInfo) {
          const newUserInfo = res.data.userInfo;
          app.globalData.userInfo = newUserInfo;
          wx.setStorageSync('userInfo', newUserInfo);
          this.setData({
            totalPoints: parseInt(newUserInfo.points || 0)
          });
        }
      },
      fail: (err) => {}
    });
  },

  fetchGoods() {
    const role = app.globalData.role || wx.getStorageSync('role');
    const path = role === 'procurement' 
      ? '/api/procurement/goods' 
      : '/api/points/goods';

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode === 200 && res.data.data) {
          this.setData({ goodsList: res.data.data });
        }
      }
    });
  },

  toggleHistory() {
    const isShowing = !this.data.showHistory;
    this.setData({
      showHistory: isShowing
    });
    
    if (isShowing) {
      this.fetchHistory();
    }
  },

  fetchHistory() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    const role = app.globalData.role || wx.getStorageSync('role');
    const path = role === 'procurement'
      ? '/api/procurement/points/history'
      : '/api/points/history';

    wx.showLoading({ title: '加载中' });
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          const list = res.data.data.map(item => ({
            id: item.id,
            desc: item.description,
            date: new Date(item.created_at).toLocaleString(),
            type: item.type,
            amount: item.amount
          }));
          this.setData({ history: list });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '加载失败', icon: 'none' });
      }
    });
  },

  exchange(e) {
    const item = e.currentTarget.dataset.item;
    
    // 检查库存
    if (item.stock <= 0) {
      wx.showToast({
        title: '该商品已售罄',
        icon: 'none'
      });
      return;
    }

    if (this.data.totalPoints < item.points) {
      wx.showToast({
        title: '积分不足',
        icon: 'none'
      });
      return;
    }

    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    const role = app.globalData.role || wx.getStorageSync('role');
    const exchangePath = role === 'procurement'
      ? '/api/procurement/points/exchange'
      : '/api/points/exchange';

    wx.showModal({
      title: '确认兑换',
      content: `确定消耗 ${item.points} 积分兑换 ${item.name} 吗？`,
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '兑换中' });
          
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: exchangePath,
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: {
              user_id: userInfo.id,
              item_id: item.id,
              points: item.points,
              item_name: item.name
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '兑换成功', icon: 'success' });
                const newPoints = this.data.totalPoints - item.points;
                const newGoodsList = this.data.goodsList.map(g => {
                  if (g.id === item.id) {
                    return { ...g, sold: g.sold + 1, stock: g.stock - 1 };
                  }
                  return g;
                });
                this.setData({ 
                  totalPoints: newPoints,
                  goodsList: newGoodsList
                });
                userInfo.points = newPoints;
                app.globalData.userInfo = userInfo;
              } else {
                wx.showToast({
                  title: apiRes.data.message || '兑换失败',
                  icon: 'none'
                });
              }
            },
            fail: () => {
              wx.hideLoading();
              wx.showToast({ title: '网络错误', icon: 'none' });
            }
          });
        }
      }
    });
  }
})
