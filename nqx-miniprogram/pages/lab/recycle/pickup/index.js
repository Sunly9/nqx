const app = getApp();

Page({
  data: {
    contact: '',
    phone: '',
    address: '',
    weight: '',
    /*
     * 回收品类。
     *
     * 原来的 `typeList/typeIndex`（纽扣锂电池/普通干电池/其他废旧电池）是**死字段**：
     * 既没有提交给后端，也没有任何报价对应它 —— 用户选了等于没选。
     * 现在换成后端真正用来算积分的 recycle_category（六档，50~200 积分/kg）。
     *
     * 为什么这件事重要：后端 `resolvePointsPerKg` 按品类单价算积分，
     * 不传品类就一律用默认 100 积分/kg。用户交氧化银（180/kg）会拿到偏低的分，
     * 而页面上完全没有地方能让他察觉。
     */
    categories: [],
    categoryIndex: -1,
    categoryName: '',
    pointsPerKg: 0,
    /** 按 (预估重量 × 品类单价) 向下取整，仅作展示 */
    estimatedPoints: 0,
    /*
     * ⚠️ 这里原来有 baseFee=20 / pricePerKg=5 / totalFee，页面据此算出一笔
     * "上门服务费"，并在确认弹窗里问"预计总费用 X 元，是否确认？"。
     *
     * 但后端 RecycleOrderService 里 `order.setTotalAmount(0)` —— 回收单没有
     * 支付环节，报酬是以**积分**形式发放的。也就是说那笔钱从未存在过，
     * 页面在向用户展示一笔不会发生的收费。
     *
     * 上门回收是免费的，所以这里不再计算任何费用。
     */
    pickupTime: '' // 预约时间
  },

  onLoad() {
    // 默认预约明天上午9点
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(9, 0, 0, 0);
    this.setData({
      pickupTime: tomorrow.toISOString().slice(0, 19).replace('T', ' ')
    });
    this.fetchCategories();
  },

  /** 拉取六档回收品类（无需登录），垫片映射 /api/recycle/categories → /app/recycle-category/list */
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
        console.log('[pickup] 回收品类加载完成，共', list.length, '个');
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

  onWeightInput(e) {
    this.recalcPoints(e.detail.value);
  },

  /** 重量或品类变化都要重算预计积分；向下取整，与后端保持一致 */
  recalcPoints(weightValue) {
    const weight = parseFloat(weightValue);
    const perKg = this.data.pointsPerKg;
    const points = (!weight || isNaN(weight) || !perKg)
      ? 0
      : Math.floor(weight * perKg);
    this.setData({ estimatedPoints: points });
  },

  selectAddress() {
    wx.navigateTo({
      url: '/pages/address/list/index?mode=select'
    });
  },

  /*
   * 原来这里是 calculateFee()：按 weight 算出处理费与总费用。
   *
   * 已删除 —— 上门回收不收费，没有可算的金额。
   *
   * 同理，`bindTypeChange` 也删掉了：它维护的 typeIndex 从来没被提交，
   * 属于"看起来能用、实际无效"的控件。现在换成了真正的回收品类选择。
   */

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

    const userInfo = app.globalData.userInfo;
    if (!userInfo) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      return;
    }

    const category = this.data.categories[this.data.categoryIndex];

    wx.showModal({
      title: '确认预约',
      // 不再提金额：上门回收免费，报一个"总费用"只会让用户以为要被扣款
      content: '确认提交上门回收预约？工作人员会按预约时间上门，现场称重后发放积分。',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '提交中' });
          
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: '/api/orders/create',
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: {
              user_id: userInfo.id,
              type: 'pickup',
              weight: this.data.weight,
              contact_name: this.data.contact,
              contact_phone: this.data.phone,
              address: this.data.address,
              pickup_time: this.data.pickupTime,
              // 后端字段名是下划线的 recycle_category_id（DTO 上的 @JsonProperty）
              recycle_category_id: category.id
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({
                  title: '预约成功',
                  icon: 'success'
                });
                setTimeout(() => {
                  wx.navigateBack();
                }, 1500);
              } else {
                wx.showToast({
                  title: apiRes.data.message || '预约失败',
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
      }
    });
  }
})
