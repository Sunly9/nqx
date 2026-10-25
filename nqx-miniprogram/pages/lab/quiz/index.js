const app = getApp();

Page({
  data: {
    loading: true,
    hasAnswered: false,
    question: null,
    selectedOption: null,
    showResult: false,
    isCorrect: false,
    explanation: '',
    pointsAwarded: 0,
    correctIndex: -1
  },

  onLoad() {
    this.fetchDailyQuiz();
  },

  fetchDailyQuiz() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) {
      wx.showToast({ title: '请先登录', icon: 'none' });
      setTimeout(() => wx.navigateBack(), 1500);
      return;
    }

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/quiz/daily',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: { user_id: userInfo.id },
      success: (res) => {
        if (res.statusCode === 200) {
          if (res.data.hasAnswered) {
            this.setData({
              loading: false,
              hasAnswered: true
            });
          } else {
            const question = res.data.data;
            if (typeof question.options === 'string') {
              try {
                question.options = JSON.parse(question.options);
              } catch (e) {}
            }
            this.setData({
              loading: false,
              hasAnswered: false,
              question: question
            });
          }
        } else {
          this.setData({ loading: false });
          wx.showToast({ title: '获取题目失败', icon: 'none' });
        }
      },
      fail: () => {
        this.setData({ loading: false });
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  },

  handleSelect(e) {
    if (this.data.showResult) return; // 已提交不可选
    const index = e.currentTarget.dataset.index;
    this.setData({ selectedOption: index });
  },

  submitAnswer() {
    if (this.data.selectedOption === null) return;

    const userInfo = app.globalData.userInfo;
    wx.showLoading({ title: '提交中' });

    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/quiz/submit',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: {
        user_id: userInfo.id,
        question_id: this.data.question.id,
        answer_index: this.data.selectedOption
      },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          const { isCorrect, pointsAwarded, explanation, correctIndex } = res.data;
          this.setData({
            showResult: true,
            isCorrect,
            pointsAwarded,
            explanation,
            correctIndex
          });
          if (isCorrect) {
            app.globalData.userInfo.points += pointsAwarded;
            wx.showToast({ title: `积分 +${pointsAwarded}`, icon: 'success' });
          } else {
            wx.vibrateShort();
          }
        } else {
          wx.showToast({ title: res.data.message || '提交失败', icon: 'none' });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '网络错误', icon: 'none' });
      }
    });
  },

  navBack() {
    wx.navigateBack();
  }
})
