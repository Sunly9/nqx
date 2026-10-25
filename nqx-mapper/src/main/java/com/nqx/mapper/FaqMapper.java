package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.Faq;
import org.apache.ibatis.annotations.Mapper;

/**
 * 常见问题 Mapper。
 *
 * <p>旧版把 FAQ 拆成 {@code faqs} 与 {@code procurement_faqs} 两张一模一样的表，
 * 这里统一为一张，用 {@code faq_type} 区分展示端（1回收端 2采购端 3通用），
 * 查询时带上类型条件即可。
 */
@Mapper
public interface FaqMapper extends BaseMapper<Faq> {
}
