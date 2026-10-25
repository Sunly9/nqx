const app = getApp();

Page({
  data: {
    product_id: null,
    reviews: [],
    loading: false
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ product_id: options.id });
      this.fetchReviews(options.id);
    }
  },

  fetchReviews(id) {
    this.setData({ loading: true });
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
          this.setData({ reviews, loading: false });
        }
      },
      fail: () => {
        this.setData({ loading: false });
      }
    });
  },

  previewImage(e) {
    const { src, list } = e.currentTarget.dataset;
    wx.previewImage({
      current: src,
      urls: list
    });
  }
});
