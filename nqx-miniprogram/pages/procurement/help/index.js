const app = getApp();

Page({
  data: {
    faqList: []
  },

  onLoad() {
    this.fetchFaqs();
  },

  fetchFaqs() {
    wx.showLoading({ title: '加载中' });
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/procurement/faqs',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          const list = res.data.data.map(item => ({
            ...item,
            open: false
          }));
          this.setData({ faqList: list });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '加载失败', icon: 'none' });
      }
    });
  },

  toggleFaq(e) {
    const index = e.currentTarget.dataset.index;
    const currentStatus = this.data.faqList[index].open;
    
    const newList = this.data.faqList.map((item, i) => {
      if (i === index) {
        return { ...item, open: !currentStatus };
      } else {
        return { ...item, open: false };
      }
    });

    this.setData({
      faqList: newList
    });
  },

  makePhoneCall() {
    wx.makePhoneCall({
      phoneNumber: '400-123-4567'
    });
  },

  copyEmail() {
    wx.setClipboardData({
      data: 'support@procurement.com',
      success: () => {
        wx.showToast({
          title: '邮箱已复制',
          icon: 'success'
        });
      }
    });
  }
})
