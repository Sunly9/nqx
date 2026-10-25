package com.nqx.pojo.vo.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台积分账户列表项。
 *
 * <p>{@code balance} 与「累计获得 − 累计消耗」应当始终相等。
 * 若在页面上发现不等，说明有代码绕过了 {@code PointsService} 直接改了余额 ——
 * 这也是 {@code totalEarn}/{@code totalSpend} 存在的意义：
 * <b>它们不只是给用户看的统计，更是账目自洽性的校验位。</b>
 */
@Data
public class AdminPointsAccountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private String nickname;

    private String avatarUrl;

    /** 当前可用积分 */
    private Integer balance;

    /** 累计获得 */
    private Integer totalEarn;

    /** 累计消耗 */
    private Integer totalSpend;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
