const app = getApp();

Page({
  data: {
    products: [],

    /** 搜索关键词（用于回显与清空） */
    keyword: '',

    loading: false
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({
        selected: 0
      });
      // 商城页也刷一次角标：用户很可能刚从商品详情"加购"回来，
      // 若只靠 tabBar 组件 attached 时算的那一次，角标会一直不更新
      if (typeof this.getTabBar().updateBadge === 'function') {
        this.getTabBar().updateBadge();
      }
    }
    this.fetchProducts();
  },

  /**
   * 拉商品列表。
   *
   * 后端的筛选参数是 `name`（模糊），不是旧前端习惯用的 `keyword` ——
   * 传错参数名时后端会**静默忽略**，返回全部数据，看起来像"搜索没生效"。
   */
  fetchProducts() {
    const kw = (this.data.keyword || '').trim();
    const query = kw ? '?name=' + encodeURIComponent(kw) : '';

    this.setData({ loading: true });
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/procurement/products' + query,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode === 200) {
          const records = (res.data && res.data.data) || [];
          this.setData({
            products: records.map((item) => ({
              ...item,
              price: item.price,
              thumb: item.image
            }))
          });
        } else {
          wx.showToast({ title: '商品加载失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.showToast({ title: '网络错误，请检查后端是否已启动', icon: 'none' });
      },
      complete: () => {
        this.setData({ loading: false });
        wx.stopPullDownRefresh();
      }
    });
  },

  onPullDownRefresh() {
    this.fetchProducts();
  },

  /**
   * 搜索：**调后端**，不是在前端过滤当前页。
   *
   * 原来那版是：
   *     const filtered = this.data.products.filter(p => p.name.includes(keyword));
   * 只过滤"已经加载出来的那 10 条"，商品在第 2 页就永远搜不到，
   * 而且注释自己都写着 "Client-side filtering for mock"。
   * 后端其实一直支持 name 模糊查询，只是一直没人调用它。
   */
  onSearch(e) {
    const keyword = (e.detail.value || '').trim();
    this.setData({ keyword: keyword });
    this.fetchProducts();
  },

  /** 清空搜索框 */
  onSearchClear() {
    this.setData({ keyword: '' });
    this.fetchProducts();
  },

  navToDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/procurement/product/detail/index?id=${id}`
    });
  }
});
