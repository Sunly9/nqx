const app = getApp();

Page({
  data: {
    addressList: [],
    isSelectMode: false
  },

  onLoad(options) {
    if (options.mode === 'select') {
      this.setData({ isSelectMode: true });
      wx.setNavigationBarTitle({ title: '选择收货地址' });
    }
  },

  onShow() {
    this.fetchAddressList();
  },

  /*
   * 拉取地址列表。
   *
   * ⚠️ 这里改掉了两个「静默失败」的写法 —— 它们正是"明明存进去了却看着像没存"的元凶：
   *
   *   1. 原实现在 userInfo 缺失时直接 `return`，页面就永远停在「暂无收货地址」。
   *      用户看到的空列表与"数据库里真的没有"长得一模一样，无法区分。
   *      现在会明确提示登录态失效。
   *
   *   2. 原实现直接把 res.data.data 塞进 addressList。但如果某天后端把返回
   *      从「裸数组」改成「分页体」，这里会静默拿到一个对象，
   *      `addressList.length` 是 undefined，页面又是空白且不报错。
   *      现在两种形状都兼容，并且会在控制台打出实际收到的形状。
   */
  fetchAddressList() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) {
      // 不静默返回：让"未登录"与"没有地址"在界面上可区分
      console.warn('[address/list] 缺少登录信息，无法拉取地址');
      this.setData({ addressList: [] });
      wx.showToast({ title: '登录状态已失效，请重新进入小程序', icon: 'none' });
      return;
    }

    const role = app.globalData.role || wx.getStorageSync('role');
    const path = role === 'procurement'
      ? '/api/procurement/address/list'
      : '/api/address/list';

    wx.showLoading({ title: '加载中' });
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: path,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode !== 200) {
          return;
        }
        // 兼容「裸数组」与「分页体 {records:[]}」两种形状
        const payload = res.data && res.data.data;
        let list = [];
        if (Array.isArray(payload)) {
          list = payload;
        } else if (payload && Array.isArray(payload.records)) {
          list = payload.records;
        }
        console.log('[address/list] 收到地址条数 =', list.length);
        this.setData({ addressList: list });
      },
      fail: (err) => {
        wx.hideLoading();
        console.error('[address/list] 请求失败', err);
        wx.showToast({ title: '加载失败，请下拉重试', icon: 'none' });
      }
    });
  },

  addAddress() {
    wx.navigateTo({
      url: '/pages/address/edit/index'
    });
  },

  editAddress(e) {
    const id = e.currentTarget.dataset.id;
    const address = this.data.addressList.find(item => item.id === id);
    // 传递完整对象需要转字符串
    wx.navigateTo({
      url: `/pages/address/edit/index?id=${id}&data=${encodeURIComponent(JSON.stringify(address))}`
    });
  },

  /*
   * 选中地址后回填到上一个页面。
   *
   * ⚠️ 这里踩过一个坑：原本只设置了 contact / phone / address 三个字段，
   * 但**采购下单确认页校验的是 displayAddress**，而它从未被赋值 ——
   * 结果是用户明明选了地址，点提交仍然提示「请选择收货地址」。
   *
   * 根因是两个调用方期望的字段名不同：
   *   · 回收下单页（寄送/上门）读的是 address（拼接后的字符串）
   *   · 采购下单确认页读的是 displayAddress
   * 而本页面是两者共用的。既然是共用组件，就一次性把两个名字都写上 ——
   * 「让调用方各自适配」会让这类不一致反复出现。
   *
   * 另外保留完整对象（addressObj），方便将来需要省市区分开用的页面。
   */
  selectAddress(e) {
    if (!this.data.isSelectMode) return;

    const item = e.currentTarget.dataset.item;
    const pages = getCurrentPages();
    const prevPage = pages[pages.length - 2]; // 上一个页面

    if (prevPage) {
      const fullAddress = `${item.province}${item.city}${item.district}${item.detail}`;
      prevPage.setData({
        contact: item.name,
        phone: item.phone,
        address: fullAddress,
        // 采购下单确认页真正校验的字段
        displayAddress: fullAddress,
        // 完整对象备用
        addressObj: item
      });
      wx.navigateBack();
    }
  }
})
