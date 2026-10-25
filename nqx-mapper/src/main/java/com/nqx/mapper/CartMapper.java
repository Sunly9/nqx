package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.Cart;
import org.apache.ibatis.annotations.Mapper;

/**
 * 购物车 Mapper。
 *
 * <p>本表没有 {@code deleted} 列 —— 购物车是临时数据，删除即物理删除。
 * 表上有唯一索引 {@code uk_user_product (user_id, product_id)}，
 * 兜住"并发重复加购产生两行"的情况：重复插入会抛
 * {@code DuplicateKeyException}，由 Service 捕获后转为"累加数量"。
 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {
}
