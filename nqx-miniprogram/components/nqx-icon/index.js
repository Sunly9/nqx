/**
 * ============================================================================
 *  nqx-icon —— 通用矢量图标组件
 * ============================================================================
 *
 *  用法：
 *
 *    <!-- 1. 先在页面的 json 里注册（漏了这一句小程序会报"组件未找到"） -->
 *    { "usingComponents": { "nqx-icon": "/components/nqx-icon/index" } }
 *
 *    <!-- 2. 再在 wxml 里用 -->
 *    <nqx-icon name="cart" size="24" color="#1989fa" />
 *
 *  【为什么要把 SVG 编译成 style 字符串放在 JS 里，而不是写在 wxml 里】
 *
 *  data URI 里含有 `%`、`;`、`,`、`%23` 这些字符，如果直接在 wxml 里拼：
 *
 *    style="background-image: url('data:image/svg+xml,{{...}}')"
 *
 *  会有两个问题：一是 wxml 的表达式里写三元/拼接可读性极差；
 *  二是 data URI 很长（每个约 400~700 字符），把它塞进模板会让
 *  wxml 每次 re-render 都重复拼接一遍字符串。
 *  放到 observer 里算，只在**属性真的变了**的时候才算一次，是更划算的。
 *
 *  【降级策略】
 *
 *  图标名写错时**不抛异常**，只 `console.warn` 然后渲染成空。
 *  理由：一个图标名拼错不该让整页白屏 —— 页面其他内容对用户是有价值的。
 *  而 warn 保证开发阶段能在控制台第一时间发现。
 *  注意 SVG 路径本身写错时**浏览器不会报任何错**，只会画出奇怪形状或空白，
 *  所以新增图标后请务必跑 `node scripts/render-icon-sheet.js` 肉眼确认。
 * ============================================================================
 */

const { ICON_PATHS, iconUrl, COLORS } = require('../../utils/icons');

/** 默认尺寸（px）。20 大致对应原来 emoji 继承 40rpx 的视觉大小 */
const DEFAULT_SIZE = 20;

Component({
  options: {
    // 允许页面样式影响组件内部节点，便于个别位置（如头像角标）微调
    addGlobalClass: true
  },

  properties: {
    /** 图标名，必须是 utils/icons.js 里 ICON_PATHS 的键 */
    name: {
      type: String,
      value: ''
    },
    /** 尺寸，单位 px（小程序里 style 写 px 才是物理像素无关的逻辑像素） */
    size: {
      type: Number,
      value: DEFAULT_SIZE
    },
    /** 描边颜色 */
    color: {
      type: String,
      value: COLORS.gray
    }
  },

  data: {
    /** 计算好的内联样式；hasIcon 为 false 时不渲染节点 */
    style: '',
    hasIcon: false
  },

  observers: {
    /**
     * 三个属性任意一个变化都重算样式。
     *
     * 用 observers 而不是自己写 setData 钩子，是因为属性可能来自
     * 父级的动态绑定（例如 `size="{{item.size}}"`），只有 observer
     * 才能在父级数据变化时被可靠地触发一次。
     */
    'name, size, color': function (name, size, color) {
      const path = ICON_PATHS[name];

      if (!path) {
        // 名字不存在：喊一声，然后降级为"不显示"，不要连累整页
        console.warn(
          '[nqx-icon] 未定义的图标名: "' + name + '"。' +
          '请在 utils/icons.js 的 ICON_PATHS 里补上，可用图标共 ' +
          Object.keys(ICON_PATHS).length + ' 个。'
        );
        if (this.data.hasIcon) {
          this.setData({ hasIcon: false, style: '' });
        }
        return;
      }

      // 兜住脏数据：size 传了 0 / NaN / 负数时回落到默认值，
      // 否则会生成 `width:0px` 或 `width:NaNpx`，图标静默消失
      const px = Number(size) > 0 ? Math.round(Number(size)) : DEFAULT_SIZE;
      const stroke = color || COLORS.gray;

      // background-image 用**单引号**包裹 —— data URI 内部的双引号
      // 已被 encodeURIComponent 编码为 %22，单引号是安全的（见 icons.js 注释）
      const style =
        'width:' + px + 'px;' +
        'height:' + px + 'px;' +
        "background-image:url('" + iconUrl(path, stroke) + "');" +
        'background-size:100% 100%;' +
        'background-repeat:no-repeat;' +
        'background-position:center;';

      // 内容和上次一致就不 setData，避免无意义的视图层 diff
      if (style !== this.data.style) {
        this.setData({ hasIcon: true, style: style });
      } else if (!this.data.hasIcon) {
        this.setData({ hasIcon: true });
      }
    }
  }
});
