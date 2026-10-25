package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.ProductReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 商品评价 Mapper。
 */
@Mapper
public interface ProductReviewMapper extends BaseMapper<ProductReview> {

    /**
     * 统计某商品的评分概况（平均分、评价数、好评数）。
     *
     * <p>SQL 见 {@code resources/mapper/ProductReviewMapper.xml}。
     *
     * <p><b>为什么用聚合查询而不是在 product 表上冗余 rating / review_count 字段？</b>
     * 冗余字段读起来快，但必须保证每次评价、删评价、隐藏评价时都同步更新，
     * 漏一次就永久不准（而且很难发现）。本项目评价量小，直接聚合更可靠。
     * 若评价量增长到需要冗余，正确做法是由定时任务或消息队列做异步汇总，
     * 而不是在业务代码里散落 UPDATE。
     */
    Map<String, Object> selectRatingStats(@Param("productId") Long productId);
}
