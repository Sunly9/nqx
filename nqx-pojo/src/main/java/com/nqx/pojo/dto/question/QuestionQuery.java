package com.nqx.pojo.dto.question;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 后台题库多条件检索条件。
 */
@Data
public class QuestionQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 题干关键词，模糊匹配 */
    private String content;

    /** 题型 1单选 2多选 3判断 */
    private Integer questionType;

    /** 难度 1易 2中 3难 */
    private Integer difficulty;

    /** 状态 0草稿 1已发布 2已下线 */
    private Integer status;

    /** 发布/生效日期起 */
    private LocalDate startDate;

    /** 发布/生效日期止 */
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
