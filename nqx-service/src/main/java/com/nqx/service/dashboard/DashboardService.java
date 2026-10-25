package com.nqx.service.dashboard;

import com.nqx.mapper.DashboardMapper;
import com.nqx.pojo.enums.OrderStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据看板服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 趋势图默认统计天数 */
    private static final int DEFAULT_TREND_DAYS = 30;

    /** 趋势图最大统计天数，防止前端传 3650 天把数据库拖垮 */
    private static final int MAX_TREND_DAYS = 180;

    private final DashboardMapper dashboardMapper;

    public Map<String, Object> overview() {
        Map<String, Object> result = dashboardMapper.selectOverview();
        return result == null ? new LinkedHashMap<>() : result;
    }

    public List<Map<String, Object>> recycleTrend(Integer days) {
        LocalDate startDate = LocalDate.now().minusDays(normalizeDays(days) - 1L);
        return dashboardMapper.selectRecycleTrend(startDate);
    }

    public List<Map<String, Object>> pointsTrend(Integer days) {
        LocalDate startDate = LocalDate.now().minusDays(normalizeDays(days) - 1L);
        return dashboardMapper.selectPointsTrend(startDate);
    }

    /**
     * 订单状态分布。
     *
     * <p>状态的中文说明在这里由枚举补上，而不是在 SQL 里写 {@code CASE WHEN} ——
     * 否则同一套状态文案会同时存在于 SQL 与 Java 两处，
     * 改动时漏掉任何一处都会让看板与列表页显示不一致。
     */
    public List<Map<String, Object>> orderStatusDistribution() {
        List<Map<String, Object>> rows = dashboardMapper.selectOrderStatusDistribution();
        for (Map<String, Object> row : rows) {
            Object statusCode = row.get("status");
            if (statusCode instanceof Number number) {
                OrderStatusEnum status = OrderStatusEnum.of(number.intValue());
                row.put("statusText", status == null ? "未知" : status.getDesc());
            }
        }
        return rows;
    }

    public List<Map<String, Object>> topProducts(Integer limit) {
        int safeLimit = (limit == null || limit < 1) ? 10 : Math.min(limit, 50);
        return dashboardMapper.selectTopProducts(safeLimit);
    }

    /** 归一化统计天数，带上限保护 */
    private int normalizeDays(Integer days) {
        if (days == null || days < 1) {
            return DEFAULT_TREND_DAYS;
        }
        return Math.min(days, MAX_TREND_DAYS);
    }
}
