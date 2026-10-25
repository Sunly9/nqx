package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分账户概览。
 */
@Data
public class PointsAccountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前可用积分 */
    private Integer balance;

    /** 累计获得 */
    @JsonProperty("total_earn")
    private Integer totalEarn;

    /** 累计消耗 */
    @JsonProperty("total_spend")
    private Integer totalSpend;

    public static PointsAccountVO of(Integer balance, Integer totalEarn, Integer totalSpend) {
        PointsAccountVO vo = new PointsAccountVO();
        vo.setBalance(balance == null ? 0 : balance);
        vo.setTotalEarn(totalEarn == null ? 0 : totalEarn);
        vo.setTotalSpend(totalSpend == null ? 0 : totalSpend);
        return vo;
    }
}
