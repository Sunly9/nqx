package com.nqx.pojo.dto.question;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 后台答题记录检索条件。
 *
 * <p>时间范围用 {@code LocalDate} 而不是 {@code LocalDateTime}：
 * 运营筛"谁在哪天答对了几道题"是按自然日思考的，
 * 让他去填时分秒既别扭、又容易把当天数据漏掉。
 * 日期在 SQL 里用 {@code >= startDate AND < endDate + 1 天} 的下闭区间表达，
 * 由 Service 层补足，调用方不需要知道这个细节。
 */
@Data
public class AdminAnswerQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户昵称，模糊匹配（需 JOIN app_user） */
    private String nickname;

    /** 用户 ID，精确匹配 */
    private Long userId;

    /** 题目 ID，精确匹配 */
    private Long questionId;

    /** 是否答对 0否 1是 */
    private Integer isCorrect;

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
