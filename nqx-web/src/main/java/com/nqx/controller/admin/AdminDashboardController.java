package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.api.Result;
import com.nqx.service.dashboard.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端数据看板接口。
 *
 * <p>全部只读，统一用一个权限码 {@code dashboard:view} 控制 ——
 * 看板是一个整体页面，为一个页面的五个图表配五个权限码没有实际意义，
 * 只会让角色配置更繁琐。
 */
@Tag(name = "管理端-数据看板", description = "总览指标与运营趋势统计")
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "总览指标", description = "用户数、订单数、回收总重、碳减排、发放积分、商品数")
    @SaCheckPermission(value = "dashboard:view", type = "admin")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(dashboardService.overview());
    }

    @Operation(summary = "回收量趋势", description = "按日统计已完成的回收单数量与重量")
    @SaCheckPermission(value = "dashboard:view", type = "admin")
    @GetMapping("/recycle-trend")
    public Result<List<Map<String, Object>>> recycleTrend(
            @RequestParam(value = "days", defaultValue = "30") Integer days) {
        return Result.success(dashboardService.recycleTrend(days));
    }

    @Operation(summary = "订单状态分布", description = "按状态与订单类型分组统计")
    @SaCheckPermission(value = "dashboard:view", type = "admin")
    @GetMapping("/order-status")
    public Result<List<Map<String, Object>>> orderStatus() {
        return Result.success(dashboardService.orderStatusDistribution());
    }

    @Operation(summary = "积分趋势", description = "按日统计积分发放与消耗")
    @SaCheckPermission(value = "dashboard:view", type = "admin")
    @GetMapping("/points-trend")
    public Result<List<Map<String, Object>>> pointsTrend(
            @RequestParam(value = "days", defaultValue = "30") Integer days) {
        return Result.success(dashboardService.pointsTrend(days));
    }

    @Operation(summary = "热门商品 Top N", description = "按下单件数排序")
    @SaCheckPermission(value = "dashboard:view", type = "admin")
    @GetMapping("/top-product")
    public Result<List<Map<String, Object>>> topProduct(
            @RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        return Result.success(dashboardService.topProducts(limit));
    }
}
