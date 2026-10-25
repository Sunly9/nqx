package com.nqx.pojo.dto.points;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 后台积分检索条件。
 *
 * <p>积分账户与积分流水两个列表共用同一个查询对象。
 * 为什么不各建一个？因为它们的可用筛选条件几乎完全重合
 * （都是"哪个用户、什么时间段"），而积分的核心查询场景就是
 * 「按用户查他所有的积分变动」—— 两个列表是同一个问题的两种视角
 * （当前余额 vs 变动明细），筛选条件自然一致。
 */
@Data
public class AdminPointsQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID，精确匹配 */
    private Long userId;

    /** 用户昵称，模糊匹配（需要 JOIN app_user） */
    private String nickname;

    /** 变动类型，见 PointsChangeTypeEnum（仅流水列表使用） */
    private Integer changeType;

    /** 业务单号，模糊匹配（仅流水列表使用） */
    private String bizNo;

    /** 起始日期（含） */
    private LocalDate startDate;

    /** 截止日期（含） */
    private LocalDate endDate;

    private Long pageNum;

    private Long pageSize;

    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
