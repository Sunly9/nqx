package com.nqx.pojo.dto.product;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品多条件检索条件。
 *
 * <p>这个类的主要用途是演示 MyBatis 动态 SQL：所有字段都是可选的，
 * 前端传哪个就拼哪个条件，不传就不参与 WHERE。
 */
@Data
public class ProductQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品名称，模糊匹配 */
    private String name;

    /** 分类 ID，等值匹配 */
    private Long categoryId;

    /** 商品类型 1采购商品 2积分兑换商品 3两者皆可 */
    private Integer productType;

    /**
     * 商品类型集合，用于"匹配多个类型"的场景。
     *
     * <p>典型用例是积分商城：需要同时取出「仅积分兑换（2）」与
     * 「采购兑换皆可（3）」的商品，而单值的 {@code productType}
     * 无法表达"2 或 3"。用集合 + MyBatis 的 {@code <foreach>}
     * 生成 {@code IN (...)}，比让前端连调两次接口再合并要干净。
     */
    private List<Integer> productTypes;

    /** 状态 0下架 1上架 */
    private Integer status;

    /** 是否热门 0否 1是 */
    private Integer isHot;

    /** 价格区间下限（分） */
    private Integer minPrice;

    /** 价格区间上限（分） */
    private Integer maxPrice;

    /** 积分价区间下限 */
    private Integer minPoints;

    /** 积分价区间上限 */
    private Integer maxPoints;

    /** 库存低于该值时命中 —— 后台"库存预警"筛选 */
    private Integer stockLessThan;

    /** 页码，从 1 开始 */
    private Long pageNum;

    /** 每页条数 */
    private Long pageSize;

    /**
     * 安全的页码。
     *
     * <p>不在这里直接信任前端传值：传 {@code pageNum=0} 或负数在部分数据库方言下
     * 会生成非法 SQL（{@code LIMIT -10, 10}），必须归一化。
     */
    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    /**
     * 安全的每页条数，带上限保护。
     *
     * <p>若前端传 {@code pageSize=999999}，MySQL 会老老实实去捞近百万行，
     * 单次请求就能把数据库连接池占满、内存打爆。
     * 在查询条件这一层就把它夹到上限，是最省事也最可靠的防线
     * （MyBatis-Plus 的 {@code PaginationInnerInterceptor.setMaxLimit} 是第二道保险）。
     */
    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
