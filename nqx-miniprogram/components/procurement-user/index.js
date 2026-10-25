const app = getApp();

Component({
  data: {
    userInfo: null
  },

  lifetimes: {
    attached() {
      // 在组件实例进入页面节点树时执行
      this.setData({
        userInfo: app.globalData.userInfo || {}
      });
    }
  },

  pageLifetimes: {
    show() {
      // 页面被展示
      this.setData({
        userInfo: app.globalData.userInfo || {}
      });
      
      // Update TabBar state if needed
      if (typeof this.getTabBar === 'function' && this.getTabBar()) {
        this.getTabBar().setData({
          selected: 2
        })
      }
    }
  },

  methods: {
    navToOrder(e) {
      const status = e.currentTarget.dataset.status;
      wx.navigateTo({
        url: `/pages/procurement/orders/index?status=${status}`
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
        url: '/pages/procurement/help/index'
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
  }
})