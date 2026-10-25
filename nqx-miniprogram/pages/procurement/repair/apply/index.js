const app = getApp();
const { uploadImage, isTempPath } = require('../../../../utils/upload');

Page({
  data: {
    order_id: null,
    description: '',
    images: []
  },

  onLoad(options) {
    if (options.order_id) {
      this.setData({ order_id: options.order_id });
    }
  },

  handleInput(e) {
    this.setData({ description: e.detail.value });
  },

  chooseImage() {
    wx.chooseImage({
      count: 3 - this.data.images.length,
      success: (res) => {
        // In a real app, upload these files to server here
        // For now, we just use the temp path
        const newImages = this.data.images.concat(res.tempFilePaths);
        this.setData({ images: newImages });
      }
    });
  },

  deleteImage(e) {
    const index = e.currentTarget.dataset.index;
    const images = this.data.images;
    images.splice(index, 1);
    this.setData({ images });
  },

  submitRepair() {
    if (!this.data.description) return;
    const userInfo = app.globalData.userInfo;
    if (!userInfo) {
       wx.showToast({ title: '请先登录', icon: 'none' });
       return;
    }

    wx.showLoading({ title: '提交中...', mask: true });

    /*
     * ⚠️ 先把凭证图片上传，再提交维修申请。
     *
     * 原来这里直接把 `this.data.images`（临时路径）提交给后端
     * （代码里留着 "In a real app, upload these files to server here"）。
     * 临时路径只在当前微信会话有效，所以申请提交后当天能看到凭证，
     * 第二天维修师傅再点开就变成裂图 —— 而接口一直 200，没有报错。
     *
     * 凭证照片是判定保修责任的关键材料，丢了会直接影响处理结果，
     * 所以这里必须上传成持久化地址。
     */
    const uploadTask = (async () => {
      const result = [];
      for (const img of this.data.images) {
        result.push(isTempPath(img) ? await uploadImage(img, 'repair') : img);
      }
      return result;
    })();

    uploadTask
      .then((imageUrls) => {
        wx.cloud.callContainer({
          env: 'your-cloudrun-env-id',
          service: 'app',
          path: '/api/procurement/repair/apply',
          method: 'POST',
          header: { 'content-type': 'application/json' },
          data: {
            user_id: userInfo.id,
            order_id: this.data.order_id,
            description: this.data.description,
            images: imageUrls
          },
          success: (res) => {
            wx.hideLoading();
            if (res.statusCode === 200) {
              // 注意：垫片的形状是 { type: 'warranty' | 'paid' }（见 repair/apply 的
              // DATA_NOT_WRAPPED 例外），所以这里读 res.data.type 而不是 res.data.data.type
              const type = res.data.type;
              const msg = type === 'warranty'
                ? '申请成功，您的设备在保修期内！'
                : '申请成功，已过保修期，将会有专人联系您报价。';
              wx.showModal({
                title: '提交成功',
                content: msg,
                showCancel: false,
                success: () => {
                  wx.navigateBack();
                }
              });
            } else {
              wx.showToast({
                title: (res.data && res.data.message) || '提交失败',
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
        wx.hideLoading();
        wx.showToast({ title: err.message || '凭证上传失败，请重试', icon: 'none' });
      });
  }
});
