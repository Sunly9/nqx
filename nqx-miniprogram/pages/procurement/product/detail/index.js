const app = getApp();

Page({
  data: {
    product: {},
    reviews: [],
    /*
     * 购买数量。
     *
     * 旧版把数量写死在「加入购物车 / 立即购买」里（永远是 1），
     * 用户想买 3 件只能先去购物车再加两次。
     * 这里补上选择器，并把选择结果带到加购与下单两处。
     */
    count: 1
  },

  onLoad(options) {
    const id = options.id;
    if (id) {
      this.fetchProductDetail(id);
      this.fetchReviews(id);
    }
  },

  fetchProductDetail(id) {
     wx.cloud.callContainer({
       env: 'your-cloudrun-env-id',
       service: 'app',
       path: `/api/procurement/products/${id}`,
       method: 'GET',
       header: { 'content-type': 'application/json' },
       success: (res) => {
         if (res.statusCode === 200) {
           const product = res.data.data;
           if (product) {
             this.setData({ 
               product: {
                 ...product,
                 price: product.price,
                 thumb: product.image,
                 gallery: product.gallery || [product.image]
               }
             });
           }
         }
       }
     });
  },

  fetchReviews(id) {
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: `/api/procurement/products/${id}/reviews`,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode === 200) {
          const reviews = res.data.data.map(r => ({
            ...r,
            formattedDate: new Date(r.created_at).toLocaleDateString()
          }));
          this.setData({ reviews });
        }
      }
    });
  },

  navToReviews() {
    wx.navigateTo({
      url: `/pages/procurement/product/reviews/index?id=${this.data.product.id}`
    });
  },

  navToCart() {
    wx.switchTab({
      url: '/pages/procurement/cart/index'
    });
  },

  /*
   * 数量加减。
   *
   * 上限取商品库存（stock 缺失时兜底 99）——在加购/下单之前就拦住，
   * 比等到下单时后端返回"库存不足"体验好得多。
   * 下限固定为 1：数量为 0 的订单没有意义。
   */
  incCount() {
    const max = (this.data.product && this.data.product.stock) || 99;
    if (this.data.count >= max) {
      wx.showToast({ title: '已达库存上限', icon: 'none' });
      return;
    }
    this.setData({ count: this.data.count + 1 });
  },

  decCount() {
    if (this.data.count <= 1) {
      return;
    }
    this.setData({ count: this.data.count - 1 });
  },

  addToCart() {
    const product = this.data.product;
    const addCount = this.data.count;
    const cart = wx.getStorageSync('cart') || [];
    
    // Check if item exists
    const index = cart.findIndex(item => item.id === product.id);
    if (index > -1) {
      // 累加用户本次选择的数量，而不是固定 +1
      cart[index].count += addCount;
    } else {
      cart.push({
        ...product,
        count: addCount,
        selected: true
      });
    }
    
    app.globalData.cart = cart;
    wx.setStorageSync('cart', cart);
    
    wx.showToast({
      title: `已加入 ${addCount} 件`,
      icon: 'success'
    });
  },

  buyNow() {
    const product = this.data.product;
    const userInfo = app.globalData.userInfo;
    
    if (!userInfo || !userInfo.id) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    if (product.stock <= 0) {
      wx.showToast({ title: '库存不足', icon: 'none' });
      return;
    }

    // 构造订单商品数据（数量取用户选择的件数）
    const products = [{
      id: product.id,
      name: product.name,
      price: product.price,
      count: this.data.count,
      image: product.image || product.thumb
    }];

    // Store data for confirm page
    wx.setStorageSync('temp_order_products', products);
    wx.setStorageSync('temp_order_total', product.price);

    wx.navigateTo({
        url: '/pages/procurement/orders/confirm/index',
        success: (res) => {
            res.eventChannel.emit('acceptDataFromOpenerPage', {
                products: products,
                totalAmount: product.price
            });
        }
    });
  }
})
