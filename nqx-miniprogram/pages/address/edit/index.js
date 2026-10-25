const app = getApp();

Page({
  data: {
    isEdit: false,
    id: null,
    region: ['北京市', '北京市', '东城区'],
    form: {
      name: '',
      phone: '',
      province: '北京市',
      city: '北京市',
      district: '东城区',
      detail: '',
      is_default: false
    }
  },

  onLoad(options) {
    if (options.id && options.data) {
      const data = JSON.parse(decodeURIComponent(options.data));
      this.setData({
        isEdit: true,
        id: options.id,
        region: [data.province, data.city, data.district],
        form: {
          name: data.name,
          phone: data.phone,
          province: data.province,
          city: data.city,
          district: data.district,
          detail: data.detail,
          is_default: !!data.is_default
        }
      });
      wx.setNavigationBarTitle({ title: '编辑地址' });
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({
      [`form.${field}`]: e.detail.value
    });
  },

  onRegionChange(e) {
    const value = e.detail.value;
    this.setData({
      region: value,
      'form.province': value[0],
      'form.city': value[1],
      'form.district': value[2]
    });
  },

  onSwitchChange(e) {
    this.setData({
      'form.is_default': e.detail.value
    });
  },

  saveAddress() {
    const { name, phone, detail } = this.data.form;
    if (!name || !phone || !detail) {
      wx.showToast({ title: '请填写完整信息', icon: 'none' });
      return;
    }

    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.showLoading({ title: '保存中' });
    
    const role = app.globalData.role || wx.getStorageSync('role');
    const isProcurement = role === 'procurement';
    
    let path = '';
    if (this.data.isEdit) {
      path = isProcurement 
        ? `/api/procurement/address/edit/${this.data.id}`
        : `/api/address/edit/${this.data.id}`;
    } else {
      path = isProcurement
        ? `/api/procurement/address/add`
        : `/api/address/add`;
    }
      
    const method = this.data.isEdit ? 'PUT' : 'POST';

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path,
      method,
      header: { 'content-type': 'application/json' },
      data: {
        user_id: userInfo.id,
        ...this.data.form
      },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          wx.showToast({ title: '保存成功', icon: 'success' });
          setTimeout(() => wx.navigateBack(), 1500);
        } else {
          wx.showToast({ title: '保存失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  },

  deleteAddress() {
    wx.showModal({
      title: '确认删除',
      content: '确定要删除该地址吗？',
      success: (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '删除中' });
          
          const role = app.globalData.role || wx.getStorageSync('role');
          const deletePath = role === 'procurement'
            ? `/api/procurement/address/delete/${this.data.id}`
            : `/api/address/delete/${this.data.id}`;

          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: deletePath,
            method: 'DELETE',
            header: { 'content-type': 'application/json' },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                wx.showToast({ title: '删除成功', icon: 'success' });
                setTimeout(() => wx.navigateBack(), 1500);
              } else {
                wx.showToast({ title: '删除失败', icon: 'none' });
              }
            }
          });
        }
      }
    });
  }
})
