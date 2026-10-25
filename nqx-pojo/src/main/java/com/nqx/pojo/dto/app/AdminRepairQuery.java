package com.nqx.pojo.dto.app;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 管理端 - 维修工单检索条件。
 *
 * <p>所有条件可选，前端传哪个就拼哪个 WHERE（与其它 Query DTO 约定一致）。
 *
 * <p>分页参数声明成可空并在 {@code safeXxx} 里给默认值：导出/统计之类的调用
 * 不需要分页，若声明成必填就会逼着调用方塞假值。
 */
@Data
public class AdminRepairQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 申请单号，模糊匹配 */
    private String applyNo;

    /** 申请人昵称，模糊匹配（关联 app_user） */
    private String nickname;

    /** 商品名称，模糊匹配 */
    private String productName;

    /** 状态 1待处理 2处理中 3已完成 4已驳回 */
    private Integer status;

    /**
     * 保修判定：warranty / paid。
     *
     * <p>单独作为一个筛选条件，是因为运营最常见的两个问题正好各对应一个值：
     * 「还有哪些保修期内的工单没处理」（要免费修，优先做口碑）与
     * 「过保的报价单有哪些」（要收钱，涉及开票与回款）。
     */
    private String warrantyType;

    /** 申请时间起（含） */
    private LocalDate startDate;

    /** 申请时间止（含） */
    private LocalDate endDate;

    private Long pageNum;

    private Long pageSize;

    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    public long safePageSize() {
        long size = pageSize == null || pageSize < 1 ? 10L : pageSize;
        return Math.min(size, 100L);
    }
}
