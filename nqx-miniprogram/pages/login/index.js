// 注释掉API_BASE_URL，因为云托管不需要域名
// const { API_BASE_URL } = require('../../utils/env');
const { uploadImage, isTempPath } = require('../../utils/upload');
const app = getApp();

Page({
  data: {
    showLoginModal: false,
    selectedRole: '',
    step: 1, // 1: Login Method, 2: Profile Form
    tempAvatarUrl: '',
    tempNickName: ''
  },

  onSelectRole(e) {
    const type = e.currentTarget.dataset.type;
    this.setData({
      selectedRole: type,
      showLoginModal: true,
      step: 1
    });
  },

  closeModal() {
    this.setData({
      showLoginModal: false,
      step: 1,
      tempAvatarUrl: '',
      tempNickName: ''
    });
  },

  backToStep1() {
    this.setData({
      step: 1
    });
  },

  onChooseAvatar(e) {
    const { avatarUrl } = e.detail;
    this.setData({
      tempAvatarUrl: avatarUrl
    });
  },

  onNicknameChange(e) {
    this.setData({
      tempNickName: e.detail.value
    });
  },

  onNicknameInput(e) {
    this.setData({
      tempNickName: e.detail.value
    });
  },

  // 第一步：检查用户是否存在
  handleCheckLogin() {
    const that = this;
    const role = this.data.selectedRole;
    
    wx.showLoading({ title: '检查中...' });
    
    wx.login({
      success: (res) => {
        if (res.code) {
          // 云托管接口路径：根据角色区分
          const apiPath = role === 'procurement' ? '/api/procurement/login' : '/api/login';
          
          // 尝试获取现有用户信息（如果有）以便更新
          const existingUserInfo = wx.getStorageSync('userInfo') || app.globalData.userInfo;
          const requestData = {
            code: res.code,
            role: role,
            checkUser: true
          };

          if (existingUserInfo) {
            requestData.userInfo = existingUserInfo;
          }

          // 替换为wx.cloud.callContainer请求
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id', // 你的云托管环境ID
            service: 'app', // 你的云托管服务名
            path: apiPath, // 接口路径（去掉域名，只保留路由）
            method: 'POST',
            data: requestData,
            header: {
              'content-type': 'application/json'
            },
            success: (apiRes) => {
              wx.hideLoading();
              
              if (apiRes.statusCode === 200) {
                // 后端返回三种情况逻辑不变
                if (apiRes.data.status === 'new_user') {
                  that.setData({ step: 2 });
                } else if (apiRes.data.token && apiRes.data.userInfo) {
                  // 老用户，直接登录成功
                  that.handleLoginSuccess(apiRes.data);
                } else {
                  wx.showToast({ title: '登录异常', icon: 'none' });
                }
              } else {
                 wx.showToast({ title: '服务异常', icon: 'none' });
              }
            },
            fail: (err) => {
              wx.hideLoading();
              console.error('检查用户登录状态失败：', err);
              wx.showToast({ title: '网络错误', icon: 'none' });
            }
          });
        }
      }
    });
  },

  // 第二步：新用户提交信息并注册
  handleFinalLogin() {
    this.performLoginWithProfile();
  },

  performLoginWithProfile() {
    const that = this;
    const role = this.data.selectedRole;
    const userInfo = {
      nickName: this.data.tempNickName || '微信用户',
      avatarUrl: this.data.tempAvatarUrl || ''
    };

    wx.showLoading({ title: '注册中...' });

    // 需要重新 login 获取新的 code
    wx.login({
      success: (res) => {
        if (res.code) {
          // 替换为wx.cloud.callContainer请求
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id', // 你的云托管环境ID
            service: 'app', // 你的云托管服务名
            path: '/api/login', // 注册接口路径
            method: 'POST',
            data: {
              code: res.code,
              role: role,
              userInfo: userInfo
            },
            header: {
              'content-type': 'application/json'
            },
            success: (apiRes) => {
              wx.hideLoading();
              if (apiRes.statusCode === 200) {
                that.handleLoginSuccess(apiRes.data);
              } else {
                wx.showToast({ title: apiRes.data.message || '注册失败', icon: 'none' });
              }
            },
            fail: (err) => {
              wx.hideLoading();
              console.error('用户注册失败：', err);
              wx.showToast({ title: '网络错误', icon: 'none' });
            }
          });
        }
      }
    });
  },

  handleLoginSuccess(data) {
    const { token, userInfo } = data;
    const role = this.data.selectedRole;

    wx.setStorageSync('token', token);
    wx.setStorageSync('userInfo', userInfo);
    wx.setStorageSync('role', role);
    
    app.globalData.userInfo = userInfo;
    app.globalData.role = role;

    /*
     * ⚠️ 这里是本轮补上的关键一环：把「完善资料」里填的昵称与头像真正保存下来。
     *
     * 原来的实现把 tempNickName / tempAvatarUrl 收在 data 里，然后……**什么也没做**。
     * 上一步选的头像、填的昵称，登录后全部丢弃，用户看到的是后端返回的默认昵称
     * 和空头像 —— 而界面上完全看不出"没保存"，只会觉得"填了怎么没用"。
     *
     * 现在：登录成功后（此时才拿到 token，上传接口要求登录态）先上传头像换成
     * 持久化的 /files/... 地址，再把昵称+头像一起写回资料。
     *
     * 失败不阻塞登录：资料没保存上只是"昵称头像没变"，不影响使用。
     * 为了这点小事让用户登不进去，是得不偿失的。
     */
    const pendingNickname = (this.data.tempNickName || '').trim();
    const pendingAvatar = this.data.tempAvatarUrl || '';

    const saveProfile = () => {
      if (!pendingNickname && !pendingAvatar) {
        return Promise.resolve();
      }
      const avatarTask = (pendingAvatar && isTempPath(pendingAvatar))
        ? uploadImage(pendingAvatar, 'avatar').catch((err) => {
            // 头像上传失败仍保存昵称 —— 只丢一半比全丢好
            console.warn('[login] 头像上传失败，仅保存昵称', err);
            return '';
          })
        : Promise.resolve(pendingAvatar);

      return avatarTask.then((avatarPath) => {
        const payload = {};
        if (pendingNickname) {
          payload.nickname = pendingNickname;
        }
        if (avatarPath) {
          payload.avatar_url = avatarPath;
        }
        if (!payload.nickname && !payload.avatar_url) {
          return undefined;
        }
        return new Promise((resolve) => {
          wx.cloud.callContainer({
            env: 'your-cloudrun-env-id',
            service: 'app',
            path: role === 'procurement' ? '/api/procurement/user/update' : '/api/user/update',
            method: 'POST',
            header: { 'content-type': 'application/json' },
            data: payload,
            success: (res) => {
              if (res.statusCode === 200) {
                if (payload.nickname) {
                  userInfo.nickname = payload.nickname;
                }
                if (payload.avatar_url) {
                  userInfo.avatar_url = payload.avatar_url;
                }
                app.globalData.userInfo = userInfo;
                wx.setStorageSync('userInfo', userInfo);
              }
              resolve();
            },
            // 失败也 resolve：不让资料保存拖累登录
            fail: () => resolve()
          });
        });
      });
    };

    saveProfile()
      .catch((err) => {
        console.warn('[login] 完善资料保存失败（不影响登录）', err);
      })
      .then(() => {
        wx.showToast({
          title: '登录成功',
          icon: 'success'
        });

        this.closeModal();

        setTimeout(() => {
          if (role === 'lab') {
            wx.reLaunch({ url: '/pages/lab/home/index' });
          } else {
            wx.reLaunch({ url: '/pages/procurement/home/index' });
          }
        }, 1000);
      });
  },

  handlePhoneLogin(e) {
    if (e.detail.errMsg === "getPhoneNumber:ok") {
       wx.showLoading({ title: '验证中...' });
       console.log('Phone Code:', e.detail.code);
       
       wx.hideLoading();
       wx.showModal({
         title: '提示',
         content: '手机号登录接口暂未对接，请使用微信一键登录',
         showCancel: false
       });
    } else {
      console.log('用户拒绝手机号授权');
    }
  }
});