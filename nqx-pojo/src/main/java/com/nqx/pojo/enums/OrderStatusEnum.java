package com.nqx.pojo.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 订单状态。
 *
 * <h2>为什么两套状态机用完全不同的编码区间？</h2>
 *
 * <p>回收单用 10~30，采购单用 40~50，只有「已取消」一个状态被共用。
 * 这样设计看似有冗余（{@link #RECYCLE_FINISHED} 与 {@link #PURCHASE_FINISHED}
 * 都是"已完成"），但换来一个很重要的性质：
 * <b>只看状态码就能判断出这是哪一类订单</b>。
 *
 * <p>若两类订单共用 1/2/3/4 这套编码，那么"状态 3"在回收单里是"已回收待结算"、
 * 在采购单里是"待收货"——同一个数字两种含义。排查线上问题时，
 * 光看数据库里的 {@code status} 列根本判断不出订单处于什么阶段，
 * 必须同时把 {@code order_type} 也捞出来对照，极易出错。
 *
 * <p>用少量编码冗余换取状态的可读性与自解释性，是值得的。
 */
@Getter
public enum OrderStatusEnum {

    // ==================== 回收单 10 ~ 30 ====================

    /** 用户已提交，等待管理员审核 */
    PENDING_AUDIT(10, "待审核", OrderTypeEnum.RECYCLE),

    /** 管理员审核通过，等待上门回收 / 用户投递 */
    AUDIT_PASSED(11, "待回收", OrderTypeEnum.RECYCLE),

    /** 已回收，等待管理员确认结算并发放积分 */
    COLLECTED(12, "待结算", OrderTypeEnum.RECYCLE),

    /** 回收单终态：积分已发放 */
    RECYCLE_FINISHED(13, "已完成", OrderTypeEnum.RECYCLE),

    /** 管理员驳回（如品类不符、数量虚报） */
    REJECTED(20, "已驳回", OrderTypeEnum.RECYCLE),

    // ==================== 采购单 40 ~ 50 ====================

    /** 待支付（模拟结账；积分兑换不走这个状态，直接进入待发货） */
    PENDING_PAY(40, "待支付", OrderTypeEnum.PURCHASE),

    /** 已支付，等待管理员发货 */
    PAID(41, "待发货", OrderTypeEnum.PURCHASE),

    /** 已发货，等待用户确认收货 */
    DELIVERED(42, "待收货", OrderTypeEnum.PURCHASE),

    /** 采购单终态 */
    PURCHASE_FINISHED(43, "已完成", OrderTypeEnum.PURCHASE),

    /** 超时未支付，由延迟队列自动关闭 */
    CLOSED(50, "已关闭", OrderTypeEnum.PURCHASE),

    // ==================== 两类共用 ====================

    /**
     * 用户主动取消。
     *
     * <p>{@code orderType} 为 {@code null} 表示两类订单都可进入本状态。
     * 用 null 而不是"随便指定一个类型"，是为了让 {@link #belongsTo} 的语义清晰：
     * 不限定类型 = 谁都适用。
     */
    CANCELLED(30, "已取消", null);

    private final Integer code;
    private final String desc;

    /** 归属的订单类型；{@code null} 表示两类订单通用 */
    private final OrderTypeEnum orderType;

    OrderStatusEnum(Integer code, String desc, OrderTypeEnum orderType) {
        this.code = code;
        this.desc = desc;
        this.orderType = orderType;
    }

    /**
     * 由状态码反查枚举。
     *
     * <p>参数类型是 {@code Integer} 而不是 {@code Short}：数据库的 SMALLINT
     * 在本项目的实体里统一映射为 {@code Integer}（见实体类），
     * 若枚举用 {@code Short}，每一处调用都要写 {@code .shortValue()}，
     * 徒增噪音且极易漏改。让枚举与实体保持同一类型，摩擦最小。
     */
    public static OrderStatusEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 该状态是否属于指定订单类型 */
    public boolean belongsTo(OrderTypeEnum type) {
        return this.orderType == null || this.orderType == type;
    }

    /**
     * 是否为终态。
     *
     * <p>终态订单不允许再发生任何流转。把判断收敛在这个方法里，
     * 避免业务代码里到处写 {@code status == 13 || status == 43 || status == 30 ...}。
     */
    public boolean isFinalStatus() {
        return this == RECYCLE_FINISHED
                || this == PURCHASE_FINISHED
                || this == REJECTED
                || this == CANCELLED
                || this == CLOSED;
    }

    /** 是否允许用户取消。已进入履约流程（如已发货）的订单不允许取消 */
    public boolean cancellableByUser() {
        return this == PENDING_AUDIT || this == AUDIT_PASSED || this == PENDING_PAY;
    }
}
