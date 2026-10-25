package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.product.RecycleCategoryQuery;
import com.nqx.pojo.entity.RecycleCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 回收品类 Mapper。
 *
 * <p>单表操作（按 ID 查、插入、更新、逻辑删除）交给 {@code BaseMapper} 自动生成，
 * 只有「多条件分页检索」手写 XML —— 因为它的条件数量随前端传参动态变化，
 * 用 Wrapper 写会变成一长串 {@code .eq(cond, ...)} {@code .like(cond, ...)}，
 * 可读性差，也和项目里其他检索接口（{@code ProductMapper}）的写法不一致。
 */
@Mapper
public interface RecycleCategoryMapper extends BaseMapper<RecycleCategory> {

    /**
     * 管理端回收品类多条件分页检索。
     *
     * <p>SQL 见 {@code resources/mapper/RecycleCategoryMapper.xml}，使用
     * {@code <where>} + {@code <if>} 动态拼接，未传的条件完全不参与 WHERE。
     * 该 XML 里<b>自己带上了 {@code deleted = 0}</b>：MyBatis-Plus 的逻辑删除
     * 对手写 SQL 完全不生效，漏写会让已删除的品类继续出现在列表里。
     *
     * @param page  分页对象，由 MyBatis-Plus 分页插件自动补 LIMIT 与 COUNT 查询
     * @param query 查询条件（分页参数已在 {@code safePageNum/safePageSize} 里做过归一化）
     */
    IPage<RecycleCategory> selectPageByCondition(IPage<RecycleCategory> page,
                                                 @Param("query") RecycleCategoryQuery query);
}
