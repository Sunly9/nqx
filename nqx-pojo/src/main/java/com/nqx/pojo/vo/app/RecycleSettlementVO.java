package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 回收结算结果。
 *
 * <p>把"这单给了多少积分、减了多少碳"一次性返回给调用方，
 * 而不是让前端结算完再发一次请求去查 —— 管理后台点完"确认回收"
 * 需要立刻给操作员一个明确的反馈（发了多少积分），
 * 少一次往返也少一次数据可能已变的困惑。
 */
@Data
public class RecycleSettlementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonProperty("order_no")
    private String orderNo;

    /** 现场称重的实际重量（kg） */
    @JsonProperty("actual_weight")
    private BigDecimal actualWeight;

    /** 本次发放的积分；仅确认收货未结算时为 null */
    @JsonProperty("points_earned")
    private Integer pointsEarned;

    /** 本次碳减排量（kg） */
    @JsonProperty("carbon_saved")
    private BigDecimal carbonSaved;

    /** 发放后用户余额；仅确认收货未结算时为 null */
    @JsonProperty("balance_after")
    private Integer balanceAfter;

    /** 结算后的订单状态说明 */
    @JsonProperty("status_text")
    private String statusText;

    public static RecycleSettlementVO of(String orderNo, BigDecimal actualWeight,
                                         Integer pointsEarned, BigDecimal carbonSaved,
                                         Integer balanceAfter, String statusText) {
        RecycleSettlementVO vo = new RecycleSettlementVO();
        vo.setOrderNo(orderNo);
        vo.setActualWeight(actualWeight);
        vo.setPointsEarned(pointsEarned);
        vo.setCarbonSaved(carbonSaved);
        vo.setBalanceAfter(balanceAfter);
        vo.setStatusText(statusText);
        return vo;
    }
}
