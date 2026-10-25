const app = getApp();

Page({
  data: {
    userInfo: null,
    role: 'lab'
  },

  onShow() {
    if (typeof this.getTabBar === 'function' &&
      this.getTabBar()) {
      this.getTabBar().setData({
        selected: 2
      })
    }
    
    // 每次显示都重新从 globalData 获取最新信息
    this.setData({
      userInfo: app.globalData.userInfo || {},
      role: app.globalData.role || 'lab'
    });
  },

  navToEditProfile() {
    wx.navigateTo({
      url: '/pages/profile/edit/index'
    });
  },

  navToOrders() {
    wx.switchTab({
      url: '/pages/order/list/index'
    });
  },

  navToPoints() {
    wx.navigateTo({
      url: '/pages/points/index'
    });
  },

  navToAddress() {
    wx.navigateTo({
      url: '/pages/address/list/index'
    });
  },

  navToHelp() {
    wx.navigateTo({
      url: '/pages/help/index'
    });
  },

  handleLogout() {
    wx.showModal({
      title: '提示',
      content: '确定要退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          wx.clearStorageSync();
          app.globalData.userInfo = null;
          app.globalData.role = null;
          app.globalData.cart = [];
          
          wx.reLaunch({
            url: '/pages/login/index'
          });
        }
      }
    });
  }
})
