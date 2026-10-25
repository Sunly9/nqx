/**
 * ============================================================================
 *  自定义 tabBar
 * ============================================================================
 *
 *  【为什么要把 emoji 换成 SVG】
 *
 *  最初这里的图标是 emoji（♻️ 📋 👤 🛍️ 🛒），问题有三个：
 *
 *  1. **改不了颜色**。emoji 是彩色字形，`style="color: xxx"` 对它完全无效。
 *     于是"未选中灰、选中亮主题色"这个最基本的 tabBar 反馈根本做不出来 ——
 *     采购端的主题色是蓝色 #1989fa，而图标永远是系统给的那几种颜色。
 *  2. **各端渲染不一致**。同一份代码在 iOS、Android、微信开发者工具上是
 *     三种长相（不同厂商的 emoji 字体差异很大），这属于"没法验收"。
 *  3. **不可控**。拿不到统一的描边粗细、视觉重心、留白，所以看起来廉价。
 *
 *  换成内联 SVG（data URI）之后：
 *  - 矢量，任意分辨率都清晰；
 *  - 描边颜色可以精确指定，未选中/选中两套颜色；
 *  - **不需要任何图片文件、不需要图标字体、不依赖网络** ——
 *    对小程序来说这点很重要：图标字体要么下载字体包（增加包体），
 *    要么走 CDN（离线打不开），而 data URI 直接内嵌在代码里。
 *
 *  【图标来源】
 *
 *  ⚠️ **图标路径与 data URI 编译逻辑已迁移到 `utils/icons.js`**。
 *  本文件不再自己维护一份 ICON_PATHS —— 页面里的 `<nqx-icon>` 组件
 *  用的也是同一份定义。之前"tabBar 一份、页面一份"的写法迟早会出现
 *  同一个图标两处描边粗细/形状不一致，而那种问题只能靠肉眼比对，
 *  非常难查。现在全项目只此一份，改一处即全局生效。
 * ============================================================================
 */

const { ICON_PATHS, iconUrl, COLORS } = require('../utils/icons');

/** 未选中：中性灰。不用纯黑，纯黑在浅色底上会显得过重 */
const COLOR_INACTIVE = COLORS.gray;

/** 选中：回收端用绿色（环保语义），采购端用蓝色（商业语义） */
const COLOR_ACTIVE_LAB = COLORS.success;
const COLOR_ACTIVE_PROCUREMENT = COLORS.primary;

Component({
  options: {
    // 允许外部（页面）样式影响组件内节点，便于各页面统一微调
    addGlobalClass: true
  },

  data: {
    selected: 0,
    color: COLOR_INACTIVE,
    selectedColor: COLOR_ACTIVE_LAB,
    list: []
  },

  lifetimes: {
    attached() {
      this.buildTabs();
    }
  },

  methods: {
    /**
     * 按当前角色构建 tab 列表。
     *
     * ⚠️ **顺序不能改**：各页面是通过 `getTabBar().setData({ selected: N })`
     * 指定选中项的（见 pages/lab/home/index.js 等 7 处），
     * 也就是说"第几个"是**写死在页面里的下标**。
     * 调整顺序会让所有页面的高亮错位，而这是运行时才看得出来的问题。
     *
     * 回收端：0 回收 / 1 订单 / 2 我的
     * 采购端：0 商城 / 1 购物车 / 2 我的
     */
    buildTabs() {
      const role = wx.getStorageSync('role') || 'lab';
      const isLab = role === 'lab';
      const activeColor = isLab ? COLOR_ACTIVE_LAB : COLOR_ACTIVE_PROCUREMENT;

      // 每个 tab 预先算好两套图标：未选中灰、选中主题色。
      // 在 WXML 里用三元表达式二选一，比在 selected 变化时重新计算要省事。
      const make = (pagePath, text, iconName) => ({
        pagePath,
        text,
        icon: iconUrl(ICON_PATHS[iconName], COLOR_INACTIVE),
        iconActive: iconUrl(ICON_PATHS[iconName], activeColor),
        badge: ''
      });

      this.setData({
        selectedColor: activeColor,
        list: isLab
          ? [
              make('/pages/lab/home/index', '回收', 'recycle'),
              make('/pages/order/list/index', '订单', 'order'),
              make('/pages/user/index', '我的', 'user')
            ]
          : [
              make('/pages/procurement/home/index', '商城', 'mall'),
              make('/pages/procurement/cart/index', '购物车', 'cart'),
              make('/pages/user/index', '我的', 'user')
            ]
      });

      this.updateBadge();
    },

    /**
     * 刷新购物车角标。
     *
     * 数据源是本地缓存 `cart`（购物车目前是纯本地实现，见
     * pages/procurement/cart/index.js），所以这里不依赖任何网络请求 ——
     * tabBar 在每个 tab 页面都会渲染，如果在这里发请求，
     * 等于**每次切页面都多打一次后端**。
     *
     * 角标显示的是**商品总件数**（各行 count 之和），而不是行数。
     * 这是电商的通行约定：加购 3 件同一商品，角标应该显示 3 ——
     * 显示 1 会让用户以为只加进去了一件。
     */
    updateBadge() {
      const list = this.data.list;
      if (!list.length) {
        return;
      }
      // 只有采购端有购物车；回收端 findIndex 返回 -1，直接跳过
      const cartIndex = list.findIndex(item => item.pagePath.indexOf('/cart/') >= 0);
      if (cartIndex < 0) {
        return;
      }

      let count = 0;
      try {
        const cart = wx.getStorageSync('cart') || [];
        if (Array.isArray(cart)) {
          count = cart.reduce((sum, item) => sum + (Number(item && item.count) || 0), 0);
        }
      } catch (e) {
        // 缓存损坏不该让 tabBar 整体渲染不出来 —— 角标只是锦上添花
        console.warn('[tab-bar] 读取购物车缓存失败，角标按 0 处理', e);
      }

      const badge = count > 99 ? '99+' : String(count);
      // 值没变就不 setData，避免一次无意义的视图层更新
      if (list[cartIndex].badge !== badge) {
        this.setData({ ['list[' + cartIndex + '].badge']: badge });
      }
    },

    onTap(e) {
      const { path, index } = e.currentTarget.dataset;

      // 已经在当前 tab 上就不重复 switchTab（会触发一次无意义的页面切换）
      if (index === this.data.selected) {
        return;
      }
      wx.switchTab({ url: path });
    }
  }
});
