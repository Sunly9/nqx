package com.nqx.pojo.enums;

import lombok.Getter;

/**
 * 订单流转动作。
 *
 * <p><b>为什么要单独定义「动作」而不是直接用目标状态？</b>
 *
 * <p>因为同一个目标状态可能由不同动作到达，而业务语义完全不同。例如：
 * <ul>
 *   <li>「已取消」可以来自用户的 CANCEL，也可以来自未来可能增加的"管理员强制取消"；</li>
 *   <li>日志与审计需要记录「谁做了什么动作」，而不只是「状态变成了什么」。
 *       产品经理问"用户是主动取消的还是超时关闭的"，只有动作能回答。</li>
 * </ul>
 *
 * <p>每个动作会对应一条 {@code order_flow} 流转日志记录。
 */
@Getter
public enum OrderActionEnum {

    /** 用户提交订单（生成订单，进入初始状态） */
    SUBMIT("提交订单"),

    // ---------- 回收单 ----------
    /** 管理员审核通过 */
    AUDIT_PASS("审核通过"),
    /** 管理员驳回 */
    REJECT("驳回"),
    /** 管理员确认已收到回收物 */
    CONFIRM_RECYCLE("确认回收"),
    /** 结算并发放积分 */
    SETTLE("结算发积分"),

    // ---------- 采购单 ----------
    /** 用户支付（本项目为模拟结账） */
    PAY("支付"),
    /** 管理员发货 */
    DELIVER("发货"),
    /** 用户确认收货 */
    CONFIRM_RECEIPT("确认收货"),

    // ---------- 通用 ----------
    /** 用户主动取消 */
    CANCEL("取消订单"),
    /** 超时未支付，由延迟队列自动关闭 */
    TIMEOUT_CLOSE("超时关闭");

    private final String desc;

    OrderActionEnum(String desc) {
        this.desc = desc;
    }
}
