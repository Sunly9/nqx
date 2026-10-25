package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.ProductCategory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品分类 Mapper。
 *
 * <p>分类的树形结构在 Java 层组装（O(n) 建树），数据库只存扁平的 parent_id，
 * 因此不需要递归 CTE 或存储过程 —— 分类总量很小（几十条），
 * 一次全量查询后在内存里建树，比递归查库更快也更简单。
 */
@Mapper
public interface ProductCategoryMapper extends BaseMapper<ProductCategory> {
}
