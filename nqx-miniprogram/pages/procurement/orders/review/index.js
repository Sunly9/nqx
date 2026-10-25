const app = getApp();
const { uploadImage, isTempPath } = require('../../../../utils/upload');

Page({
  data: {
    order_id: null,
    order: null,
    reviews: [],
    ratingTexts: ['非常差', '差', '一般', '好', '非常好']
  },

  onLoad(options) {
    if (options.order_id) {
      this.setData({ order_id: options.order_id });
      this.fetchOrder(options.order_id);
    }
  },

  fetchOrder(id) {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.showLoading({ title: '加载中...' });

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: `/api/procurement/orders/${id}`,
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        if (res.statusCode === 200) {
          const order = res.data.data;
          const reviews = order.products.map(p => ({
            product_id: p.id,
            product_name: p.name,
            product_image: p.image,
            rating: 5,
            content: '',
            images: []
          }));
          this.setData({ order, reviews });
        }
      },
      complete: () => {
        wx.hideLoading();
      }
    });
  },

  handleRating(e) {
    const { index, rating } = e.currentTarget.dataset;
    const key = `reviews[${index}].rating`;
    this.setData({ [key]: rating });
  },

  handleContentInput(e) {
    const { index } = e.currentTarget.dataset;
    const value = e.detail.value;
    const key = `reviews[${index}].content`;
    this.setData({ [key]: value });
  },

  chooseImage(e) {
    const { index } = e.currentTarget.dataset;
    const currentImages = this.data.reviews[index].images;

    wx.chooseImage({
      count: 3 - currentImages.length,
      success: (res) => {
        // In real app, upload here. For now, use temp path.
        const newImages = currentImages.concat(res.tempFilePaths);
        const key = `reviews[${index}].images`;
        this.setData({ [key]: newImages });
      }
    });
  },

  deleteImage(e) {
    const { index, imgIndex } = e.currentTarget.dataset;
    const images = this.data.reviews[index].images;
    images.splice(imgIndex, 1);
    const key = `reviews[${index}].images`;
    this.setData({ [key]: images });
  },

  submitReviews() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.showLoading({ title: '发布中...', mask: true });

    /*
     * ⚠️ 先把晒图上传，再提交评价。
     *
     * 原来这里直接把 `this.data.reviews[i].images` 里的**临时路径**提交给后端
     * （代码里还留着一句 "In real app, upload here. For now, use temp path."）。
     * 临时路径只在当前微信会话有效，所以评价发出去当天图片能看到，
     * 第二天（或换设备）就全变成裂图 —— 而接口一直返回 200，没有任何报错。
     *
     * 现在逐张上传，拿到 `/files/...` 的持久化地址后再提交。
     * 任一张上传失败就中止并提示，避免"部分图片丢失"这种更难查的中间状态。
     */
    const task = (async () => {
      const uploadedReviews = [];
      for (const item of this.data.reviews) {
        const localImages = item.images || [];
        const urlImages = [];
        for (const img of localImages) {
          // 已经是服务端地址的（例如重复提交）不再上传
          urlImages.push(isTempPath(img) ? await uploadImage(img, 'review') : img);
        }
        uploadedReviews.push({ ...item, images: urlImages });
      }
      return uploadedReviews;
    })();

    task
      .then((uploadedReviews) => {
        wx.cloud.callContainer({
          env: 'your-cloudrun-env-id',
          service: 'app',
          path: '/api/procurement/reviews/add',
          method: 'POST',
          header: { 'content-type': 'application/json' },
          data: {
            user_id: userInfo.id,
            order_id: this.data.order_id,
            reviews: uploadedReviews
          },
          success: (res) => {
            wx.hideLoading();
            if (res.statusCode === 200) {
              wx.showToast({ title: '评价成功', icon: 'success' });
              setTimeout(() => {
                wx.navigateBack();
              }, 1500);
            } else {
              wx.showToast({
                title: (res.data && res.data.message) || '发布失败',
                icon: 'none'
              });
            }
          },
          fail: () => {
            wx.hideLoading();
            wx.showToast({ title: '网络错误', icon: 'none' });
          }
        });
      })
      .catch((err) => {
        // 晒图上传失败：中止发布并说明原因，而不是静默丢失图片
        wx.hideLoading();
        wx.showToast({ title: err.message || '图片上传失败，请重试', icon: 'none' });
      });
  }
});
