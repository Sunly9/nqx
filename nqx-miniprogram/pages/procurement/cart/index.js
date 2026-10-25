const { formatPrice } = require('../../../utils/util');
const app = getApp();

Page({
  data: {
    cartList: [],
    allSelected: false,
    totalPrice: '0.00',
    totalCount: 0
  },

  onShow() {
    if (typeof this.getTabBar === 'function' &&
      this.getTabBar()) {
      this.getTabBar().setData({
        selected: 1
      })
      /*
       * 顺带刷新购物车角标。
       *
       * 购物车是纯本地实现（下面 initCart 读的就是 wx.getStorageSync('cart')），
       * 所以在别处加购之后，tabBar 上的数字不会自己变 —— 它只在
       * 组件 attached 时算过一次。页面 onShow 是唯一稳定的"回来了"信号，
       * 在这里顺手刷一下即可，成本是一次内存读取。
       *
       * 为什么不用 setData 传值、而让组件自己去读缓存：
       * 角标是 tabBar 自己的展示细节，把"我该显示几"这个问题
       * 留给它自己回答，页面就不需要知道 tabBar 内部的数据形状。
       */
      if (typeof this.getTabBar().updateBadge === 'function') {
        this.getTabBar().updateBadge();
      }
    }
    this.initCart();
  },

  initCart() {
    const cartList = wx.getStorageSync('cart') || [];
    this.setData({ cartList });
    this.calculateTotal();
  },

  toggleSelect(e) {
    const index = e.currentTarget.dataset.index;
    const cartList = this.data.cartList;
    cartList[index].selected = !cartList[index].selected;
    
    this.setData({ cartList });
    this.saveCart();
    this.calculateTotal();
  },

  toggleSelectAll() {
    const allSelected = !this.data.allSelected;
    const cartList = this.data.cartList.map(item => {
      item.selected = allSelected;
      return item;
    });
    
    this.setData({ cartList, allSelected });
    this.saveCart();
    this.calculateTotal();
  },

  updateCount(e) {
    const { index, type } = e.currentTarget.dataset;
    const cartList = this.data.cartList;
    
    if (type === 'plus') {
      if (cartList[index].count < (cartList[index].stock || 99)) {
        cartList[index].count++;
      } else {
        wx.showToast({ title: '库存不足', icon: 'none' });
        return;
      }
    } else {
      if (cartList[index].count > 1) {
        cartList[index].count--;
      }
    }
    
    this.setData({ cartList });
    this.saveCart();
    this.calculateTotal();
  },

  deleteItem(e) {
    const index = e.currentTarget.dataset.index;
    wx.showModal({
      title: '提示',
      content: '确认删除该商品吗？',
      success: (res) => {
        if (res.confirm) {
          const cartList = this.data.cartList;
          cartList.splice(index, 1);
          this.setData({ cartList });
          this.saveCart();
          this.calculateTotal();
        }
      }
    });
  },

  calculateTotal() {
    let total = 0;
    let count = 0;
    let allSelected = true;
    
    const cartList = this.data.cartList;
    if (cartList.length === 0) allSelected = false;

    cartList.forEach(item => {
      if (item.selected) {
        // Use points if available, else price
        const price = item.price;
        total += price * item.count;
        count += item.count;
      } else {
        allSelected = false;
      }
    });
    
    this.setData({
      totalPrice: total.toFixed(2), // Points are integers
      totalCount: count,
      allSelected
    });
  },

  saveCart() {
    wx.setStorageSync('cart', this.data.cartList);
    app.globalData.cart = this.data.cartList;
  },

  goShopping() {
    wx.switchTab({
      url: '/pages/procurement/home/index'
    });
  },

  async checkout() {
    if (this.data.totalCount === 0) {
      wx.showToast({
        title: '请选择商品',
        icon: 'none'
      });
      return;
    }

    const userInfo = app.globalData.userInfo;
    if (!userInfo || !userInfo.id) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    const selectedItems = this.data.cartList.filter(item => item.selected);
    
    // 构造订单商品数据
    const products = selectedItems.map(item => ({
      id: item.id,
      name: item.name,
      price: item.price,
      count: item.count,
      image: item.image
    }));

    // 获取默认地址 (暂时模拟，后续可从 API 获取)
    // const address = { ... }; // Removed in favor of confirm page
    
    // Store data for confirm page
    wx.setStorageSync('temp_order_products', products);
    wx.setStorageSync('temp_order_total', this.data.totalPrice);

    wx.navigateTo({
        url: '/pages/procurement/orders/confirm/index',
        success: (res) => {
            // Pass data via event channel if possible
            res.eventChannel.emit('acceptDataFromOpenerPage', {
                products: products,
                totalAmount: this.data.totalPrice
            });
        }
    });
  }
})
