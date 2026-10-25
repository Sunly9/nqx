package com.nqx.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 数据看板 Mapper。
 *
 * <p>本 Mapper <b>不继承 BaseMapper</b> —— 它没有对应的实体表，
 * 提供的全是聚合统计查询。这是 MyBatis-Plus 与手写 SQL 分工的又一个例子：
 * 单表 CRUD 交给 MP，而多表聚合、分组统计这类"SQL 才是主角"的查询，
 * 手写才有意义（用 Wrapper 拼聚合函数会变成一堆看不懂的字符串）。
 *
 * <p><b>返回 Map 而不是实体类的理由</b>：看板的每个统计维度都不同
 * （趋势是 date+value、分布是 status+count、Top 榜是 name+count），
 * 为它们各建一个只有两三个字段的 VO 收益很低。这类只读、只用于展示的
 * 聚合结果，用 {@code Map<String, Object>} 更省事，代价是失去编译期类型检查 ——
 * 因此字段名必须与 SQL 里的别名严格一致，改名时要两处同时改。
 */
@Mapper
public interface DashboardMapper {

    /** 总览卡片：用户数、订单数、回收总重、碳减排、发放积分、商品数 */
    Map<String, Object> selectOverview();

    /**
     * 回收量趋势（按日）。
     *
     * @param startDate 起始日期（含）。只查最近 N 天，避免全表扫描
     */
    List<Map<String, Object>> selectRecycleTrend(@Param("startDate") LocalDate startDate);

    /** 订单状态分布 */
    List<Map<String, Object>> selectOrderStatusDistribution();

    /** 积分发放 / 消耗趋势（按日） */
    List<Map<String, Object>> selectPointsTrend(@Param("startDate") LocalDate startDate);

    /** 兑换 / 采购 Top 商品 */
    List<Map<String, Object>> selectTopProducts(@Param("limit") int limit);
}
