const app = getApp();
const { uploadImage, toAbsoluteUrl } = require('../../../../utils/upload');

Page({
  data: {
    contact: '',
    phone: '',
    address: '',
    weight: '',
    weightError: false,
    logisticsList: ['京东快递', '顺丰速运', '韵达快递', '圆通速递'],
    logisticsIndex: 0,
    /** 显示用（绝对地址或本地预览） */
    image: '',
    /** 保存/提交用（服务端相对路径 /files/...），上传成功后才会有值 */
    imagePath: '',
    /** 凭证上传中 —— 此时不许提交，避免提交缺少凭证 */
    imageUploading: false,
    courierNo: '', // 新增快递单号字段，用于模拟
    /*
     * 回收品类。
     *
     * ⚠️ 这是本次补上的关键一环：后端结算按 **该品类的每公斤积分** 计算
     * （AdminOrderService.resolvePointsPerKg），但小程序原来**没有让用户选品类**，
     * 于是 recycle_category_id 永远是 null，一律按默认 100 积分/kg 结算。
     *
     * 后果很具体：用户交的是氧化银（180/kg），实际只拿到 100/kg ——
     * 页面上没有任何地方能让用户发现这件事，只会觉得"积分怎么这么少"。
     */
    categories: [],
    categoryIndex: -1,
    categoryName: '',
    pointsPerKg: 0,
    /** 按 (预估重量 × 品类单价) 向下取整得到的预计积分，仅作展示 */
    estimatedPoints: 0
  },

  onLoad() {
    this.fetchCategories();
  },

  /**
   * 拉取可选品类。
   *
   * 接口是 `/app/recycle-category/list`（无需登录），垫片里映射为
   * `/api/recycle/categories`。
   *
   * 兼容两种返回形状：垫片解包后直接是数组；若哪天变成 { data: [...] }
   * 也不会让页面白屏 —— 这类"形状变了导致下拉框空白"的问题排查起来很费时。
   */
  fetchCategories() {
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/recycle/categories',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      success: (res) => {
        if (res.statusCode !== 200) {
          return;
        }
        const payload = res.data;
        const list = Array.isArray(payload)
          ? payload
          : (payload && Array.isArray(payload.data) ? payload.data : []);
        this.setData({ categories: list });
        console.log('[send] 回收品类加载完成，共', list.length, '个');
      },
      fail: () => {
        wx.showToast({ title: '回收品类加载失败，请返回重试', icon: 'none' });
      }
    });
  },

  bindCategoryChange(e) {
    const index = Number(e.detail.value);
    const c = this.data.categories[index];
    if (!c) {
      return;
    }
    this.setData({
      categoryIndex: index,
      categoryName: c.name,
      pointsPerKg: Number(c.pointsPerKg) || 0
    });
    this.recalcPoints(this.data.weight);
  },

  /** 重量或品类的任一变化都要重算预计积分 */
  recalcPoints(weightValue) {
    const weight = parseFloat(weightValue);
    const perKg = this.data.pointsPerKg;
    const points = (!weight || isNaN(weight) || !perKg)
      ? 0
      : Math.floor(weight * perKg); // 与后端一致：向下取整
    this.setData({ estimatedPoints: points });
  },

  checkWeight(e) {
    const val = parseFloat(e.detail.value);
    if (val > 2) {
      this.setData({ weightError: true });
    } else {
      this.setData({ weightError: false });
    }
    this.recalcPoints(e.detail.value);
  },

  bindLogisticsChange(e) {
    this.setData({
      logisticsIndex: e.detail.value
    });
  },

  /**
   * 选择「处理凭证」照片并**立刻上传**。
   *
   * 原来的实现只把 `res.tempFilePaths[0]` 存进 data（注释写着 "Mock upload success"），
   * 而且 `submitOrder` 的请求体里**根本没有这个字段** —— 也就是说：
   * 用户认认真真拍了张凭证照片，提交时它既不显示也不发送，等于白拍。
   *
   * 现在改成：选完就上传到服务端，拿到 `/files/...` 的持久化地址，
   * 提交时随 `images` 字段一起发给后端（RecycleOrderCreateDTO 支持该字段）。
   */
  chooseImage() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempPath = res.tempFilePaths[0];
        if (!tempPath) {
          return;
        }
        // 先显示本地预览，让用户看到"选上了"
        this.setData({ image: tempPath, imageUploading: true });
        wx.showLoading({ title: '上传中', mask: true });

        uploadImage(tempPath, 'recycle')
          .then((relativePath) => {
            wx.hideLoading();
            this.setData({
              image: toAbsoluteUrl(relativePath),
              imagePath: relativePath,
              imageUploading: false
            });
          })
          .catch((err) => {
            wx.hideLoading();
            // 上传失败就清掉预览，避免"看起来传了其实没传"
            this.setData({ image: '', imagePath: '', imageUploading: false });
            wx.showToast({ title: err.message || '凭证上传失败', icon: 'none' });
          });
      }
    });
  },

  submitOrder() {
    if (!this.data.contact || !this.data.phone || !this.data.address || !this.data.weight) {
      wx.showToast({
        title: '请填写完整信息',
        icon: 'none'
      });
      return;
    }

    // 品类必选：不选就按默认价结算，用户拿到的积分会和预期对不上
    if (this.data.categoryIndex < 0) {
      wx.showToast({
        title: '请选择电池品类',
        icon: 'none'
      });
      return;
    }

    if (this.data.weightError) {
      wx.showToast({
        title: '重量超过限制',
        icon: 'error'
      });
      return;
    }

    const userInfo = app.globalData.userInfo;
    if (!userInfo) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    // 凭证还在上传时不许提交：否则提交的单子里会缺少凭证，
    // 而用户以为自己已经传了
    if (this.data.imageUploading) {
      wx.showToast({ title: '凭证还在上传，请稍候', icon: 'none' });
      return;
    }

    wx.showLoading({ title: '提交中' });

    // 模拟生成一个快递单号
    const courierNo = 'SF' + Date.now();
    const category = this.data.categories[this.data.categoryIndex];

    const payload = {
      user_id: userInfo.id,
      type: 'send',
      weight: this.data.weight,
      contact_name: this.data.contact,
      contact_phone: this.data.phone,
      address: this.data.address,
      courier_no: courierNo,
      // 后端字段名是下划线的 recycle_category_id（DTO 上的 @JsonProperty）
      recycle_category_id: category.id
    };
    // 凭证照片：只有真正上传成功（imagePath 有值）才带上，
    // 绝不把本地临时路径发给后端 —— 那种地址服务端存下来也打不开
    if (this.data.imagePath) {
      payload.images = [this.data.imagePath];
    }

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/orders/create',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: payload,
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          wx.showToast({
            title: '下单成功',
            icon: 'success'
          });
          setTimeout(() => {
            wx.navigateBack();
          }, 1500);
        } else {
          wx.showToast({
            title: res.data.message || '下单失败',
            icon: 'none'
          });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  }
})
