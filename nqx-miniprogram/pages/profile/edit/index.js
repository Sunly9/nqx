const app = getApp();
const { uploadImage, toAbsoluteUrl, isTempPath } = require('../../../utils/upload');

/**
 * 把服务端返回的相对路径补成绝对地址（供 `<image>` 显示）。
 *
 * 换算逻辑复用 utils/upload.js —— 上传、显示、判断临时路径这三件事
 * 分散在多个页面里各写一份的话，迟早会出现"某个页面忘了补域名导致图片空白"。
 */
function toAbsolute(url) {
  return toAbsoluteUrl(url);
}

/**
 * 把绝对地址还原成相对路径（供保存）。
 *
 * 存相对路径的原因：写死 `http://127.0.0.1:8080` 后换机器、换端口、上云托管，
 * 数据库里所有头像都会失效，而且失效是静默的（图片不显示，接口照样 200）。
 *
 * 临时路径（`wxfile://` / `http://tmp/...`）返回空串 —— 让调用方知道
 * "这个头像还没上传成功"，而不是把临时路径写进数据库。
 */
function toRelative(url) {
  if (!url) {
    return '';
  }
  if (isTempPath(url)) {
    return '';
  }
  const base = require('../../../utils/env').API_BASE_URL.replace(/\/+$/, '');
  return url.indexOf(base) === 0 ? url.substring(base.length) : url;
}

Page({
  data: {
    /** 显示用：绝对地址 */
    avatarUrl: '',
    /** 保存用：相对路径（/files/...），上传成功后才会有值 */
    avatarPath: '',
    nickName: '',
    uploading: false
  },

  onLoad() {
    const userInfo = app.globalData.userInfo;
    if (userInfo) {
      this.setData({
        avatarUrl: toAbsolute(userInfo.avatar_url || ''),
        avatarPath: toRelative(userInfo.avatar_url || ''),
        nickName: userInfo.nickname || ''
      });
    }
  },

  /**
   * 选择头像后**立刻上传**。
   *
   * <h3>为什么必须上传，不能直接用返回的路径</h3>
   *
   * `wx.chooseAvatar` 返回的是**临时文件路径**（`http://tmp/xxx.jpeg` 或
   * `wxfile://tmp_xxx.jpeg`）。它有两个致命问题：
   *
   *   1. 只存在于当前微信客户端会话，**重启小程序或换设备就失效**；
   *   2. 指向手机本地文件，服务端读不到。
   *
   * 原来的实现把临时路径直接写进了 `avatar_url` —— 数据库里存的是一串
   * 明天就打不开的地址。表现是：当天换了头像，第二天变回默认，**且不报错**。
   *
   * 现在改成：选完先传到服务端，拿到 `/files/...` 的持久化地址再保存。
   */
  onChooseAvatar(e) {
    const { avatarUrl } = e.detail;
    if (!avatarUrl) {
      return;
    }

    // 先立刻把本地临时文件显示出来，避免上传期间用户以为没选中
    this.setData({ avatarUrl, uploading: true });
    wx.showLoading({ title: '上传中', mask: true });

    const restore = () => {
      const userInfo = app.globalData.userInfo || {};
      this.setData({
        avatarUrl: toAbsolute(userInfo.avatar_url || ''),
        avatarPath: toRelative(userInfo.avatar_url || '')
      });
    };

    // 上传逻辑收敛在 utils/upload.js：4 个页面共用同一份实现，
    // 避免"某个页面忘了 parse res.data"这类各处不同的小毛病。
    uploadImage(avatarUrl, 'avatar')
      .then((relativePath) => {
        wx.hideLoading();
        this.setData({
          uploading: false,
          avatarPath: relativePath,
          avatarUrl: toAbsolute(relativePath)
        });
        wx.showToast({ title: '头像已上传', icon: 'success' });
      })
      .catch((err) => {
        wx.hideLoading();
        this.setData({ uploading: false });
        // 上传失败要把预览回退成原来的头像，避免"看起来换了其实没换成"
        restore();
        wx.showToast({ title: err.message || '头像上传失败', icon: 'none' });
      });
  },

  onNicknameChange(e) {
    this.setData({
      nickName: e.detail.value
    });
  },

  /** 兼容部分基础库的输入事件名 */
  onNicknameInput(e) {
    this.setData({
      nickName: e.detail.value
    });
  },

  saveProfile() {
    if (!this.data.nickName) {
      wx.showToast({
        title: '请输入昵称',
        icon: 'none'
      });
      return;
    }

    if (this.data.uploading) {
      wx.showToast({ title: '头像还在上传，请稍候', icon: 'none' });
      return;
    }

    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.showLoading({ title: '保存中' });

    /*
     * ⚠️ 提交的是 avatarPath（相对路径），不是 avatarUrl（绝对地址）。
     *
     * 把带域名的绝对地址写进数据库，换环境后所有头像都会失效 ——
     * 而且失效是静默的（图片加载不出来，接口依然返回 200）。
     * 这里如果 avatarPath 为空（用户没换头像且原头像本来就是临时路径），
     * 就不带 avatar_url 字段，避免把已有值覆盖成空字符串。
     */
    const saveData = {
      nickname: this.data.nickName
    };
    if (this.data.avatarPath) {
      saveData.avatar_url = this.data.avatarPath;
    }

    const role = app.globalData.role || wx.getStorageSync('role');
    const updatePath = role === 'procurement'
      ? '/api/procurement/user/update'
      : '/api/user/update';

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: updatePath,
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: saveData,
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          wx.showToast({
            title: '保存成功',
            icon: 'success'
          });
          userInfo.nickname = this.data.nickName;
          if (this.data.avatarPath) {
            userInfo.avatar_url = this.data.avatarPath;
          }
          app.globalData.userInfo = userInfo;
          wx.setStorageSync('userInfo', userInfo);
          setTimeout(() => {
            wx.navigateBack();
          }, 1500);
        } else {
          wx.showToast({
            title: (res.data && res.data.message) || '保存失败',
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
});
