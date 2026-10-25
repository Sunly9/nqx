const app = getApp();

Page({
  data: {
    userInfo: null
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({
        selected: 2
      })
      // 三个采购端 tab 页都刷一次角标，保证无论从哪个页面回到 tabBar，
      // 数字都是最新的（用户可能是在任意页面加购后切过来的）
      if (typeof this.getTabBar().updateBadge === 'function') {
        this.getTabBar().updateBadge();
      }
    }
    
    this.setData({
      userInfo: app.globalData.userInfo || {}
    });
  },

  navToEditProfile() {
    wx.navigateTo({
      url: '/pages/profile/edit/index'
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