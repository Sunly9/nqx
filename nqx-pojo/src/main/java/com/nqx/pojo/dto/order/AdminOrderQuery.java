package com.nqx.pojo.dto.order;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台订单多条件检索条件。
 *
 * <p>所有字段可选，未传的条件不参与 WHERE（动态 SQL 见 {@code BizOrderMapper.xml}）。
 */
@Data
public class AdminOrderQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单号，模糊匹配 */
    private String orderNo;

    /** 用户昵称，模糊匹配（需要 JOIN app_user） */
    private String nickname;

    /** 用户 ID，精确匹配 */
    private Long userId;

    /** 订单类型 1回收 2采购 */
    private Integer orderType;

    /** 订单状态码 */
    private Integer status;

    /** 回收方式 send / pickup */
    private String recycleMethod;

    /** 提交时间范围起（含） */
    private LocalDateTime startTime;

    /** 提交时间范围止（含） */
    private LocalDateTime endTime;

    /** 积分下限 */
    private Integer minPoints;

    /** 积分上限 */
    private Integer maxPoints;

    private Long pageNum;

    private Long pageSize;

    /** 安全的页码，防止前端传 0 或负数导致非法 LIMIT */
    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    /** 安全的每页条数，带上限保护 */
    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
