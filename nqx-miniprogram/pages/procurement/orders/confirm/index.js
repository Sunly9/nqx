const app = getApp();

Page({
  data: {
    products: [],
    totalAmount: '0.00',
    address: null, // 完整的地址对象
    displayAddress: '', // 拼接后的显示字符串
    contact: '',
    phone: ''
  },

  onLoad(options) {
    // 获取传递过来的商品数据
    const eventChannel = this.getOpenerEventChannel();
    if (eventChannel && eventChannel.on) {
      eventChannel.on('acceptDataFromOpenerPage', (data) => {
        this.setData({
          products: data.products,
          totalAmount: data.totalAmount
        });
      });
    } else {
        // Fallback: try storage
        const products = wx.getStorageSync('temp_order_products');
        const totalAmount = wx.getStorageSync('temp_order_total');
        if (products) {
            this.setData({ products, totalAmount });
        }
    }
  },

  onShow() {
    // 如果没有选择地址，尝试加载默认地址
    if (!this.data.address && !this.data.contact) {
      this.fetchDefaultAddress();
    }
  },

  fetchDefaultAddress() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/procurement/address/list',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        if (res.statusCode === 200 && res.data.data.length > 0) {
          const list = res.data.data;
          const defaultAddr = list.find(item => item.is_default) || list[0];
          this.setData({
            address: defaultAddr,
            contact: defaultAddr.name,
            phone: defaultAddr.phone,
            displayAddress: `${defaultAddr.province}${defaultAddr.city}${defaultAddr.district}${defaultAddr.detail}`
          });
        }
      }
    });
  },

  selectAddress() {
    wx.navigateTo({
      url: '/pages/address/list/index?mode=select'
    });
  },

  submitOrder() {
    const { products, totalAmount, contact, phone, displayAddress } = this.data;
    const userInfo = app.globalData.userInfo;

    if (!contact || !displayAddress) {
      wx.showToast({ title: '请选择收货地址', icon: 'none' });
      return;
    }

    // Construct address object for backend
    const addressObj = {
      name: contact,
      phone: phone,
      address: displayAddress
    };

    wx.showLoading({ title: '提交订单...' });

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/procurement/orders/create',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: {
        user_id: userInfo.id,
        products: products,
        total_amount: totalAmount,
        address: addressObj
      },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          wx.showToast({ title: '下单成功', icon: 'success' });
          this.clearPurchasedItemsFromCart(products);
          setTimeout(() => {
            wx.redirectTo({
              url: '/pages/procurement/orders/index?status=pending_payment'
            });
          }, 1500);
        } else {
          wx.showToast({ title: res.data.message || '下单失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  },

  clearPurchasedItemsFromCart(purchasedProducts) {
      // 简单处理：如果 storage 里的 cart 有这些 ID 且 selected，就删掉
      let cart = wx.getStorageSync('cart') || [];
      if (cart.length > 0) {
          const purchasedIds = purchasedProducts.map(p => p.id);
          // Only remove if it was selected (to avoid removing items added for later)
          // But buyNow items might not be in cart. 
          // Safe bet: remove items that are selected AND in the purchased list.
          cart = cart.filter(item => !(purchasedIds.includes(item.id) && item.selected));
          wx.setStorageSync('cart', cart);
          app.globalData.cart = cart;
      }
  }
})
