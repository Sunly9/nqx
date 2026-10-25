package com.nqx.pojo.dto.product;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收品类分页检索条件。
 *
 * <p>所有字段都是可选的：前端传哪个条件就拼哪个，
 * 动态 SQL 的 {@code <if>} 见 {@code resources/mapper/RecycleCategoryMapper.xml}。
 *
 * <p>与 {@code ProductQuery} 一样，分页参数不在这里做注解校验，
 * 而是通过 {@link #safePageNum()} / {@link #safePageSize()} 在拼 SQL 前归一化 ——
 * 越界的页码不是「参数错误」，而是应该被修正的输入，
 * 否则用户手改 URL 里的 pageNum 就会看到 400 而不是第一页数据。
 */
@Data
public class RecycleCategoryQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 品类名称，模糊匹配 */
    private String name;

    /** 状态 0禁用 1启用，不传表示不限 */
    private Integer status;

    /** 页码，从 1 开始 */
    private Long pageNum;

    /** 每页条数 */
    private Long pageSize;

    /**
     * 安全的页码。
     *
     * <p>不信任前端传值：传 {@code pageNum = 0} 或负数，在部分数据库方言下
     * 会生成非法 SQL（{@code LIMIT -10, 10}），必须归一化。
     */
    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    /**
     * 安全的每页条数，带上限保护。
     *
     * <p>若前端传 {@code pageSize = 999999}，MySQL 会老老实实去捞近百万行，
     * 单次请求就能把连接池与内存占满。在这里夹到上限是最省事也最可靠的一道防线
     * （MyBatis-Plus 分页插件的 {@code setMaxLimit(100)} 是第二道保险）。
     */
    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
