package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.OrderFlow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单流转日志 Mapper。
 *
 * <p>本表是「只追加」的：只插入、不更新、不删除。订单的完整生命周期
 * 都能从这张表按时间顺序还原出来，是进度查询与审计的事实依据。
 *
 * <p>注意本表没有 {@code deleted} 列（流转日志不做逻辑删除），
 * 手写 SQL 时不要习惯性加 {@code deleted = 0}。
 */
@Mapper
public interface OrderFlowMapper extends BaseMapper<OrderFlow> {
}
