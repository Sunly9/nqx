package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单明细 Mapper。
 *
 * <p>目前唯一的用途是给「回收品类删除前的占用校验」提供计数能力：
 * 回收品类一旦被任何一张回收单的明细引用过，就不能再删除，
 * 否则历史订单会指向一个不存在的品类。
 *
 * <p><b>注意 {@code order_item} 表没有 {@code deleted} 列</b>，
 * 对应的 {@link OrderItem} 实体上也没有 {@code @TableLogic}。
 * 这是刻意的：订单明细是已发生的交易事实，只允许追加、不允许删除，
 * 所以这里的计数天然会把全部历史明细算进来，不需要（也不能）加
 * {@code deleted = 0} 条件。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 统计某用户已经兑换过某商品的数量，用于「每人限兑」校验。
     *
     * <p>只统计**积分兑换单**（{@code order_type=2 AND pay_type=1}），
     * 且排除已取消/已关闭的订单 —— 用户取消掉的兑换不该继续占用限兑额度，
     * 否则会出现"取消了却再也兑不了"的投诉。
     *
     * <p>SQL 见 {@code resources/mapper/OrderItemMapper.xml}。
     */
    Integer countExchangedQuantity(@Param("userId") Long userId,
                                   @Param("productId") Long productId);
}
