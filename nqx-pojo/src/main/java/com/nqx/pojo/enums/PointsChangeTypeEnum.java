package com.nqx.pojo.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 积分变动类型。
 *
 * <p>对应 {@code points_record.change_type}。类型定义得越细，
 * 后续做运营分析越方便 —— 想知道"积分主要靠什么发出去的"，
 * 按这个字段 group by 就能得到答案；若只有"增/减"两种，
 * 就永远无法区分是回收返的积分还是答题送的积分。
 *
 * <p>同时它也是<b>幂等键的一部分</b>：
 * {@code points_record} 上的唯一索引是 {@code (user_id, biz_no, change_type)}。
 * 把 change_type 纳入幂等键是有意为之 ——
 * 一笔兑换业务（biz_no 相同）先扣分（type=4），后续若发生退款（type=5），
 * 两者是两笔合法的独立流水，不能被唯一索引判为重复。
 * 若只用 {@code (user_id, biz_no)}，退款就永远记不进去。
 */
@Getter
public enum PointsChangeTypeEnum {

    /** 新用户注册奖励 */
    REGISTER(1, "注册奖励", true),

    /** 每日问答答对奖励 */
    DAILY_QUIZ(2, "每日问答", true),

    /** 回收单结算返积分 */
    RECYCLE_REWARD(3, "回收返积分", true),

    /** 积分兑换商品扣减 */
    EXCHANGE(4, "积分兑换", false),

    /** 兑换失败/取消后的积分退回 */
    EXCHANGE_REFUND(5, "兑换退回", true),

    /**
     * 管理员手动调整。
     *
     * <p>{@code income} 为 {@code null} 表示方向由变动值的正负号决定 ——
     * 管理员既可以补偿用户（加），也可以扣罚（减）。
     * 其它类型的方向是固定的，用它做校验能挡住"兑换类型却传了正数"
     * 这类会把账目搞乱的低级错误。
     */
    ADMIN_ADJUST(6, "管理员调整", null),

    /** 采购订单完成（确认收货）奖励 */
    PURCHASE_REWARD(7, "采购订单奖励", true);

    private final Integer code;
    private final String desc;

    /** 是否为收入方向；{@code null} 表示不限方向 */
    private final Boolean income;

    PointsChangeTypeEnum(Integer code, String desc, Boolean income) {
        this.code = code;
        this.desc = desc;
        this.income = income;
    }

    public static PointsChangeTypeEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验变动方向是否与类型匹配。
     *
     * @throws IllegalArgumentException 方向不符时抛出（属于编码错误，不是业务错误，
     *                                  因此用断言式异常，让它在开发阶段就暴露）
     */
    public void validateDirection(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("积分变动值不能为 0（类型：" + desc + "）");
        }
        if (income == null) {
            return;
        }
        boolean isIncome = delta > 0;
        if (income != isIncome) {
            throw new IllegalArgumentException(String.format(
                    "积分变动方向与类型不符：%s 应为%s，实际传入 %d",
                    desc, income ? "增加" : "扣减", delta));
        }
    }

    /** 是否为扣减方向，供前端展示与旧版 type 字段映射使用 */
    public boolean isSpend(int delta) {
        return delta < 0;
    }
}
