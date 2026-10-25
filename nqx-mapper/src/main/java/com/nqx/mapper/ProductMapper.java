package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.product.ProductQuery;
import com.nqx.pojo.entity.Product;
import com.nqx.pojo.vo.product.ProductVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 商品 Mapper。
 *
 * <p>这是本项目「MyBatis-Plus + 手写 XML」分工的最佳示例：
 * <ul>
 *   <li><b>单表操作</b>（按 ID 查、插入、更新、逻辑删除）由 {@code BaseMapper} 自动生成，
 *       一行 SQL 都不用写；</li>
 *   <li><b>多条件组合检索</b>手写 XML —— 因为它需要 JOIN 分类表取名称，
 *       且条件数量随前端传参动态变化。用 Wrapper 写会变成一长串
 *       {@code .eq(cond, ...)} {@code .like(cond, ...)}，可读性差且无法 JOIN 出 categoryName。</li>
 * </ul>
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 后台商品多条件分页检索。
     *
     * <p>SQL 见 {@code resources/mapper/ProductMapper.xml}，使用
     * {@code <where>} + {@code <if>} 动态拼接，未传的条件完全不参与 WHERE。
     *
     * @param page  分页对象，由 MyBatis-Plus 的分页插件自动补 LIMIT 与 COUNT 查询
     * @param query 查询条件
     */
    IPage<ProductVO> selectPageByCondition(IPage<ProductVO> page, @Param("query") ProductQuery query);

    /** 统计某分类下的商品数，用于分类删除前的占用校验 */
    Long countByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 扣减库存（防超卖）。
     *
     * <p>SQL：{@code UPDATE product SET stock = stock - ?, sold_count = sold_count + ?
     * WHERE id = ? AND stock >= ?}
     *
     * <p><b>为什么不能"先查库存再判断再更新"？</b>
     * 三步之间是竞态窗口。100 件库存、200 个并发下单时，
     * 会有多个请求同时查到"还剩 1 件"，然后各扣一次 —— 库存变成负数。
     * 把判断和扣减合并进同一条 SQL，由 InnoDB 行锁保证串行，
     * 扣不动的请求 affectedRows = 0，调用方据此抛"库存不足"。
     *
     * @return 1 表示扣减成功，0 表示库存不足
     */
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 回滚库存（订单取消 / 超时关闭 / 兑换失败）。
     *
     * <p>加上 {@code sold_count >= ?} 的下界保护：sold_count 是"累计销量"，
     * 正常不会小于单次回滚量，但若因并发或人工改库导致它偏小，
     * 无保护的自减会让它变成负数，之后所有的销量统计都会失真。
     */
    int restoreStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
