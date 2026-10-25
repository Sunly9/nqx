package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SiteSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点配置 Mapper。
 *
 * <p>注意本表没有 {@code deleted} 列（纯 KV 配置表），因此实体上没有
 * {@code @TableLogic}，查询结果也不会被自动过滤。这一点与项目里其它表不同，
 * 手写 SQL 时不要习惯性地加 {@code deleted = 0}，否则会报 Unknown column。
 */
@Mapper
public interface SiteSettingMapper extends BaseMapper<SiteSetting> {
}
