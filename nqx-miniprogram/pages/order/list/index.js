const app = getApp();

Page({
  data: {
    currentTab: 0,
    role: 'lab',
    orders: []
  },

  onShow() {
    if (typeof this.getTabBar === 'function' &&
      this.getTabBar()) {
      this.getTabBar().setData({
        selected: 1
      })
    }
    this.setData({
      role: app.globalData.role || 'lab'
    });
    this.fetchOrders();
  },

  switchTab(e) {
    const index = parseInt(e.currentTarget.dataset.index);
    this.setData({
      currentTab: index
    });
    this.fetchOrders();
  },

  fetchOrders() {
    const userInfo = app.globalData.userInfo;
    if (!userInfo) return;

    wx.showLoading({ title: '加载中' });
    
    wx.cloud.callContainer({
      env: 'your-cloudrun-env-id',
      service: 'app',
      path: '/api/orders/list',
      method: 'GET',
      header: { 'content-type': 'application/json' },
      data: {
        user_id: userInfo.id,
        /*
         * ⚠️ 必须显式传 orderType=1（回收单）。
         *
         * 原来这里只传了 user_id，而 /app/order/page 在不传 orderType 时
         * **返回该用户的全部订单（回收单 + 采购单）**。
         *
         * 后果很隐蔽：本页下面按 `status === 'pending' / 'processing' / 'completed'`
         * 做客户端筛选，而**采购单完成后后端返回的正是 'completed'** ——
         * 于是采购单会混进「已完成的回收订单」里，用户会看到自己买的电池
         * 出现在"我交的废电池"列表中。两个列表看起来都对，只是内容错位。
         *
         * 前端筛选永远替代不了后端过滤：筛选条件依赖的字段
         * （status）正是两类订单共用的，必然串。
         */
        orderType: 1
      },
      success: (res) => {
        wx.hideLoading();
        if (res.statusCode === 200) {
          let list = res.data.data;
          /*
           * tab 索引：0 全部 / 1 进行中 / 2 已完成 / 3 已驳回
           *
           * 「已驳回」单独成一个 tab：
           * 以前 REJECTED 被后端映射成 'cancelled'，这类订单既不在"进行中"
           * 也不在"已完成"，只能到"全部"里翻 —— 而它恰恰是**最需要用户关注**
           * 的状态（要去看驳回原因、要联系客服）。单独一个 tab 让它一眼可见。
           */
          if (this.data.currentTab === 1) {
            list = list.filter(o => o.status === 'pending' || o.status === 'processing');
          } else if (this.data.currentTab === 2) {
            list = list.filter(o => o.status === 'completed');
          } else if (this.data.currentTab === 3) {
            list = list.filter(o => o.status === 'rejected');
          }
          list = list.map(item => ({
            ...item,
            statusText: this.getStatusText(item.status),
            typeText: item.type === 'send' ? '寄送处理' : '上门回收',
            formattedDate: new Date(item.created_at).toLocaleString(),
            hintText: this.buildHintText(item)
          }));
          this.setData({
            orders: list
          });
        }
      },
      fail: () => {
        wx.hideLoading();
        wx.showToast({ title: '加载失败', icon: 'none' });
      }
    });
  },

  getStatusText(status) {
    const map = {
      'pending': '待处理',
      'processing': '处理中',
      'completed': '已完成',
      'cancelled': '已取消',
      // 已驳回是独立状态，不能显示成"已取消"—— 那会让人以为是自己取消的
      'rejected': '已驳回'
    };
    return map[status] || status;
  },

  /*
   * 生成进度提示文字（纯展示，不可点）。
   *
   * 【为什么原来那个「验收」按钮必须删掉】
   *
   * 旧版的「验收」会调 POST /api/orders/settle，并且把 actual_weight
   * 从**客户端**传上去，接口还没有鉴权 —— 传 99999 就能凭空刷出上百万积分。
   * 后来接口被收敛成管理端动作（POST /admin/order/{orderNo}/confirm-recycle，
   * 由工作人员现场称重后录入），但**按钮留在页面上没删**，改成点一下弹说明。
   *
   * 这仍然不对，有两层问题：
   *   1. 概念错：「验收」「称重」「结算」都是管理员的工作，买家不参与；
   *   2. 交互错：按钮看起来能操作，点了却只弹一句话，比没有按钮更困惑。
   *
   * 所以现在：**不给按钮，只给一句进度说明**。
   * 买家在这个阶段确实没有可执行的动作，如实告诉他"在等谁做什么"就够了。
   *
   * 用 status_code（数字）而不是 status（字符串）判断：
   * 字符串是给旧页面做展示分桶用的（pending/processing/completed 三个桶），
   * 会把「待审核」和「待回收」都归成同一种，没法给出精确提示。
   */
  buildHintText(item) {
    const code = item.status_code;
    const isSend = item.type === 'send';

    const hints = {
      // 10 待审核
      10: '已提交，等待工作人员审核',
      // 11 待回收
      11: isSend ? '审核通过，请把物品寄到实验室' : '审核通过，等待工作人员上门回收',
      // 12 待结算：工作人员已收到，正在称重
      12: '物品已收到，工作人员正在称重结算',
      // 13 已完成
      13: '已完成，积分已发放到账户',
      // 20 已驳回
      20: '申请被驳回，请在详情中查看原因',
      // 30 已取消
      30: '订单已取消',
      // 50 已关闭
      50: '订单已关闭'
    };

    if (hints[code]) {
      return hints[code];
    }
    // 兜底：状态码不认识时按旧字符串粗略提示，总比什么都不显示好
    if (item.status === 'completed') {
      return '已完成';
    }
    if (item.status === 'rejected') {
      return '申请被驳回';
    }
    return '';
  },

  /*
   * 复制快递单号。
   *
   * 这是买家在「已寄出、等对方签收」阶段最常用的动作（去物流网站查进度），
   * 而且完全在本地完成，不依赖后端。
   */
  copyCourier(e) {
    const no = e.currentTarget.dataset.no;
    if (!no) {
      return;
    }
    wx.setClipboardData({
      data: String(no),
      success: () => {
        // setClipboardData 自带一个"内容已复制"的提示，这里不再叠加 toast
      }
    });
  },

  /*
   * 旧函数名保留说明。
   *
   * simulateSettle（"模拟验收"）已删除：
   * 它不调任何接口，只弹一段「关于验收与结算」的说明 —— 属于"假按钮"。
   * 买家界面上不该出现「验收」这个词，相关说明改成订单卡片上的 hintText。
   */

  viewDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: `/pages/order/detail/index?id=${id}`
    });
  }

  /*
   * simulateSettle 已彻底删除（连同 WXML 里的「验收」按钮）。
   *
   * 历史：旧版点「验收」会调 POST /api/orders/settle，把 actual_weight
   * 从客户端传上去，而接口没有任何鉴权 —— 传 99999 就能凭空刷出上百万积分。
   * 该接口后来收敛成管理端动作 POST /admin/order/{orderNo}/confirm-recycle，
   * 但按钮被留了下来、改成弹一段说明，形成了"假按钮"。
   *
   * 现在买家界面上不再出现「验收」两个字：
   * 审核 / 称重 / 结算 全部是工作人员的动作，买家只需要看到进度提示
   * （见 buildHintText）。
   */
})
