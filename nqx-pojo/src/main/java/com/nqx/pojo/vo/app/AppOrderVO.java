package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.enums.OrderLegacyStatus;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 小程序端订单视图。
 *
 * <h2>为什么同时存在 status（字符串）和 statusCode（数字）</h2>
 *
 * <p>新后端内部用状态机的数字状态码（见 {@link OrderStatusEnum}），
 * 但旧版小程序的订单页读的是<b>字符串</b>状态：
 *
 * <pre>{@code
 * list = list.filter(o => o.status === 'pending' || o.status === 'processing');
 * statusText: this.getStatusText(item.status),
 * typeText: item.type === 'send' ? '寄送处理' : '上门回收',
 * }</pre>
 *
 * <p>字段名冲突只有一个位置，必须二选一：把这个位置留给旧客户端能识别的字符串，
 * 数字状态码另起名字。这是一个<b>有明确边界的兼容决定</b>：
 * <ul>
 *   <li>{@code status} —— 旧的字符串约定，供现有页面直接用；</li>
 *   <li>{@code statusCode} —— 新状态机的数字码，供新版页面与排查使用；</li>
 *   <li>{@code statusText} —— 中文说明，页面可直接展示，
 *       省掉前端自己维护一份状态映射表（那份表迟早会与后端不一致）。</li>
 * </ul>
 *
 * <h2>时间格式用 ISO-8601（带 T）而不是空格分隔</h2>
 *
 * <p>Jackson 对 {@code LocalDateTime} 默认输出 {@code 2026-10-15T23:06:13}。
 * <b>刻意不改成 {@code yyyy-MM-dd HH:mm:ss}</b>：
 * iOS 上 {@code new Date("2026-10-15 23:06:13")} 会得到 {@code Invalid Date}，
 * 而 {@code new Date("2026-10-15T23:06:13")} 正常。
 * 小程序页面里普遍用 {@code new Date(str)} 再交给格式化函数，
 * 所以带 T 的 ISO 格式反而是兼容性最好的选择。
 */
@Data
public class AppOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    @JsonProperty("order_no")
    private String orderNo;

    /** 回收方式 send / pickup。字段名沿用旧前端的 type */
    private String type;

    /** 订单业务类型 1回收 2采购（新字段，供新版页面使用） */
    @JsonProperty("order_type")
    private Integer orderType;

    /** 对外状态（旧字符串约定） */
    private String status;

    /** 数字状态码（新状态机） */
    @JsonProperty("status_code")
    private Integer statusCode;

    /** 中文状态说明，页面可直接展示 */
    @JsonProperty("status_text")
    private String statusText;

    /** 预估重量（kg） */
    private BigDecimal weight;

    /** 实际回收重量（kg），管理员确认回收后才有值 */
    @JsonProperty("actual_weight")
    private BigDecimal actualWeight;

    /** 本次获得的积分 */
    @JsonProperty("points_earned")
    private Integer pointsEarned;

    /** 本次碳减排量（kg） */
    @JsonProperty("carbon_saved")
    private BigDecimal carbonSaved;

    @JsonProperty("contact_name")
    private String contactName;

    @JsonProperty("contact_phone")
    private String contactPhone;

    /**
     * 收货/取件地址。
     *
     * <p><b>这是一个对象，不是字符串。</b>采购订单页读的是
     * {@code order.address.name / .phone / .address}（嵌套结构）。
     *
     * <p>回收单详情页原本读的是扁平的 {@code {{order.address}}}，
     * 已随本次改造改为 {@code {{order.address.address}}} ——
     * 用页面的一行改动换接口形状的统一，详见 {@link OrderAddressVO} 的说明。
     */
    private OrderAddressVO address;

    /**
     * 订单商品明细。
     *
     * <p>采购订单页需要它来渲染商品列表（{@code wx:for="{{item.products}}"}}）。
     * 注意回收单通常只有一个"品类"，用明细表同样能表达，
     * 因此回收单也会带出明细，只是页面不展示。
     */
    private List<AppOrderItemVO> products;

    /**
     * 该订单是否已经评价过。
     *
     * <p><b>为什么需要这个字段</b>：采购订单列表的「去评价」按钮原本判断
     * {@code wx:if="{{item.status === 'pending_evaluation'}}"}}，
     * 而后端订单完成后返回的 {@code status} 是 {@code 'completed'}
     * （见 {@link OrderLegacyStatus#toLegacy}）。
     * 两边对不上 → <b>那个按钮永远不会渲染 → 评价功能实际上不可达</b>。
     * 这是一个"不报错的缺失"：页面正常、接口 200，只是永远少一个按钮。
     *
     * <p><b>为什么不能只把条件改成 completed</b>：那样已经评价过的订单
     * 还会继续显示「去评价」，用户点进去重复提交会撞
     * {@code uk_order_product} 唯一索引，看到一句看不懂的错误。
     *
     * <p><b>所以让后端明确回答"这单评价过没有"</b>，
     * 前端用 {@code status === 'completed' && !reviewed} 判断。
     * 把业务事实交给掌握事实的一方回答，比让前端从状态字段里推断更可靠。
     *
     * <p>实现上注意**不要 N+1**：一页 10 条订单就查 10 次评价表是不可接受的，
     * 必须按页批量查一次再回填（见 {@code AppOrderService#loadReviewedFlags}）。
     */
    private Boolean reviewed;

    /**
     * 订单总金额，单位<b>元</b>。
     *
     * <p>字段名是下划线的 {@code total_amount}（页面模板直接引用），
     * 单位是元（页面用 {@code ¥} 展示）。新库存的是分，这里除以 100。
     */
    @JsonProperty("total_amount")
    private BigDecimal totalAmount;

    @JsonProperty("courier_no")
    private String courierNo;

    /** 预约上门时间 */
    @JsonProperty("pickup_time")
    private LocalDateTime pickupTime;

    @JsonProperty("created_at")
    private LocalDateTime createTime;

    /** 驳回原因（仅被驳回的回收单有值） */
    @JsonProperty("reject_reason")
    private String rejectReason;

    /**
     * 买家最近一次「提醒发货」的时间（从未提醒过则为 null）。
     *
     * <p>小程序用它把按钮文案从「提醒发货」换成「已提醒 · 15:30」——
     * 这是"点完不能还显示原来的标语"的关键字段。
     */
    @JsonProperty("remind_ship_at")
    private LocalDateTime remindShipAt;

    /** 买家累计提醒发货次数 */
    @JsonProperty("remind_ship_count")
    private Integer remindShipCount;

    /**
     * 现在能不能再提醒一次。
     *
     * <p><b>由后端算，不由前端算。</b>限频规则（24 小时）只应该有一份实现 ——
     * 放在前端就意味着：改规则要改两处、而且用户改本地时间就能绕过。
     */
    @JsonProperty("can_remind_ship")
    private Boolean canRemindShip;

    /** 不带明细的转换（列表页若不需要商品明细可用；详情页请用带 items 的重载） */
    public static AppOrderVO from(BizOrder order) {
        return from(order, null);
    }

    /**
     * 完整转换。
     *
     * @param items 订单明细；为 null 时 products 为空列表而非 null
     *              —— 页面上写的是 {@code wx:for="{{item.products}}"}}，
     *              给 null 会让部分基础库报错，给空数组则安静地不渲染，更安全。
     */
    public static AppOrderVO from(BizOrder order, List<OrderItem> items) {
        AppOrderVO vo = new AppOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderType(order.getOrderType());
        vo.setType(order.getRecycleMethod());
        vo.setWeight(order.getEstimatedWeight());
        vo.setActualWeight(order.getActualWeight());
        vo.setPointsEarned(order.getTotalPoints());
        vo.setCarbonSaved(order.getCarbonSaved());
        vo.setContactName(order.getContactName());
        vo.setContactPhone(order.getContactPhone());
        // 地址以对象形式下发（页面读的是 order.address.name / .phone / .address）
        vo.setAddress(OrderAddressVO.of(
                order.getContactName(), order.getContactPhone(), order.getAddressDetail()));
        vo.setCourierNo(order.getCourierNo());
        vo.setPickupTime(order.getExpectTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setRejectReason(order.getRejectReason());

        // ---- 提醒发货 ----
        vo.setRemindShipAt(order.getRemindShipAt());
        vo.setRemindShipCount(order.getRemindShipCount() == null ? 0 : order.getRemindShipCount());
        vo.setCanRemindShip(computeCanRemindShip(order));
        // 金额：库存分，页面用 ¥ 展示所以要转成元
        vo.setTotalAmount(toYuan(order.getTotalAmount()));
        vo.setProducts(items == null
                ? Collections.emptyList()
                : items.stream().map(AppOrderItemVO::from).toList());

        OrderStatusEnum status = OrderStatusEnum.of(order.getStatus());
        OrderTypeEnum orderType = OrderTypeEnum.of(order.getOrderType());
        if (status != null) {
            vo.setStatusCode(status.getCode().intValue());
            vo.setStatusText(status.getDesc());
            vo.setStatus(toLegacyStatus(orderType, status));
        } else {
            // 状态码不在枚举里说明数据异常（人工改库 / 版本回滚）。不抛异常，
            // 原样带出数字让页面能显示点什么，同时便于从日志定位。
            vo.setStatusCode(order.getStatus() == null ? null : order.getStatus().intValue());
        }
        return vo;
    }

    /**
     * 数字状态码 → 旧版字符串状态。
     *
     * <h2>⚠️ 两类订单的字符串词表完全不同</h2>
     *
     * <p>这是旧库把两类订单拆成两张表留下的后果 —— 它们各自演化出了一套状态命名：
     *
     * <pre>
     *   回收单（orders）：          pending / processing / completed / cancelled
     *   采购单（procurement_orders）：pending_payment / pending_shipment /
     *                               pending_receipt / completed / cancelled
     * </pre>
     *
     * <p>页面里的判断写得很具体，例如采购订单页：
     * <pre>{@code
     * <block wx:if="{{item.status === 'pending_payment'}}">  支付按钮
     * <block wx:if="{{item.status === 'pending_receipt'}}">  确认收货按钮
     * }</pre>
     *
     * <p><b>所以这里必须按订单类型分流。</b>如果两类订单共用一套词表
     * （比如统一返回 {@code processing}），采购订单页的所有操作按钮
     * 都会因为状态值对不上而<b>永远不显示</b> —— 页面不报错，
     * 只是"没有按钮可点"，是极难定位的一类问题。
     *
     * <h2>为什么 REJECTED 映射成 cancelled</h2>
     *
     * <p>回收单页面的状态映射表里只有 pending/processing/completed/cancelled，
     * 没有 rejected。若原样返回 {@code rejected}，页面会显示英文原字符串。
     * 映射成 {@code cancelled} 能让页面走"已取消"的展示分支，
     * 而<b>精确状态仍由 {@code statusCode}（105）与 {@code statusText}（已驳回）承载</b>，
     * 驳回原因在 {@code reject_reason} 里。
     * <b>旧的字符串只是展示分桶，数字码才是权威。</b>
     */
    /**
     * 状态字符串的映射逻辑已抽到 {@link OrderLegacyStatus}。
     *
     * <p>抽出去的原因：**同一个词表有两个方向**（输出用正向、筛选用反向），
     * 而最初只实现了正向，导致小程序把 {@code status=pending_shipment}
     * 作为查询参数发上来时后端直接 500。
     * 两个方向放在同一个类里，改一个就一定会看到另一个。
     */
    private static String toLegacyStatus(OrderTypeEnum type, OrderStatusEnum status) {
        return OrderLegacyStatus.toLegacy(type, status);
    }

    /** 判断是否回收单，供上层筛选 */
    public static boolean isRecycle(BizOrder order) {
        return OrderTypeEnum.RECYCLE.getCode().equals(order.getOrderType());
    }

    /**
     * 现在能不能再「提醒发货」。
     *
     * <p>三个条件必须同时满足：
     * <ol>
     *   <li>是采购单（回收单没有"发货"这个概念）；</li>
     *   <li>状态是「待发货」（41）—— 已发货/已完成/已取消都不需要提醒；</li>
     *   <li>距上次提醒已超过 24 小时（或从未提醒过）。</li>
     * </ol>
     *
     * <p><b>这个判断必须与 AppOrderService.remindShip 保持一致。</b>
     * 两处不一致的后果是：按钮显示可点，一点却报错"今天已提醒过" ——
     * 用户会以为系统坏了。
     */
    private static Boolean computeCanRemindShip(BizOrder order) {
        if (!OrderTypeEnum.PURCHASE.getCode().equals(order.getOrderType())) {
            return Boolean.FALSE;
        }
        if (!OrderStatusEnum.PAID.getCode().equals(order.getStatus())) {
            return Boolean.FALSE;
        }
        if (order.getRemindShipAt() == null) {
            return Boolean.TRUE;
        }
        return !order.getRemindShipAt().plusHours(REMIND_SHIP_INTERVAL_HOURS)
                .isAfter(LocalDateTime.now());
    }

    /**
     * 提醒发货的最小间隔（小时）。
     *
     * <p>与 {@code AppOrderService.REMIND_SHIP_INTERVAL_HOURS} 必须相同 ——
     * 但本类在 nqx-pojo、Service 在 nqx-service，两边不能直接引用对方的常量
     * （pojo 不能依赖 service）。所以这里各写一份，并在 Service 侧加了交叉引用注释。
     *
     * <p>真要彻底消除重复，应该把它挪到 nqx-common 的常量类里。等它第三次被用到再说 ——
     * 现在为两处重复引入一个公共常量类，收益不抵成本。
     */
    private static final int REMIND_SHIP_INTERVAL_HOURS = 24;

    /** 分 → 元 */
    private static BigDecimal toYuan(Integer cents) {
        if (cents == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
