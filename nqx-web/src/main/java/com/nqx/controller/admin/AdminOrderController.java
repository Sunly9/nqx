package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.order.AdminOrderQuery;
import com.nqx.pojo.dto.order.ConfirmRecycleDTO;
import com.nqx.pojo.vo.app.RecycleSettlementVO;
import com.nqx.pojo.vo.order.AdminOrderVO;
import com.nqx.service.order.AdminOrderService;
import com.nqx.service.order.PurchaseOrderService;
import com.nqx.service.satoken.StpAdminUtil;
import com.nqx.service.export.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端订单接口。
 *
 * <p><b>这里承载着旧版最严重漏洞的修复</b>：
 * 旧版把「结算发积分」放在小程序端接口 {@code POST /api/orders/settle} 上，
 * 且没有任何鉴权、实际重量由客户端提供 —— 任何用户都能给自己刷积分。
 * 新版把审核、确认收货、结算全部收敛到 {@code /admin/**}，
 * 每个动作都有独立的 RBAC 权限码。
 */
@Tag(name = "管理端-订单", description = "多条件检索、回收单审核/驳回/确认回收/结算")
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final PurchaseOrderService purchaseOrderService;
    private final ExportService exportService;

    @Operation(summary = "订单多条件检索",
            description = "支持订单号、用户昵称、类型、状态、回收方式、时间范围、积分区间等组合查询")
    /*
     * 这里必须用 OR 语义接受两个权限码。
     *
     * 原因：这是**一个接口服务两类订单**（通过 orderType 参数区分），
     * 但菜单与角色是按「回收订单」「采购订单」分别授权的：
     *   · 菜单「回收订单」下发 order:recycle:list
     *   · 菜单「采购订单」下发 order:purchase:list
     *
     * 若只写 order:recycle:list，一个"只管采购"的角色会看到采购订单菜单、
     * 点进去却直接 403 —— 菜单可见但页面不可用，是最让人困惑的一类权限问题。
     *
     * 为什么不用"按 orderType 分流到两个接口"？那要前端先知道该调哪个，
     * 而前端的订单页是通用的（一个页面靠 orderType 参数切换）。
     * 用 OR 保持一个入口、由权限注解表达"任一订单类型权限即可访问"，
     * 是改动最小且语义正确的做法。
     */
    @SaCheckPermission(value = {"order:recycle:list", "order:purchase:list"}, mode = SaMode.OR, type = "admin")
    @GetMapping("/page")
    public Result<PageResult<AdminOrderVO>> page(AdminOrderQuery query) {
        return Result.success(adminOrderService.page(query));
    }

    @Operation(summary = "当前订单可执行的动作",
            description = "由后端状态机下发，前端据此决定按钮的显示与隐藏，避免前后端各维护一份状态规则")
    @SaCheckPermission(value = {"order:recycle:list", "order:purchase:list"}, mode = SaMode.OR, type = "admin")
    @GetMapping("/{orderNo}/actions")
    public Result<Map<String, String>> availableActions(@PathVariable String orderNo) {
        return Result.success(adminOrderService.availableActions(orderNo));
    }

    @Operation(summary = "审核通过回收单", description = "待审核 → 待回收")
    @Log(module = "订单管理", operation = "审核通过回收单")
    @SaCheckPermission(value = "order:recycle:audit", type = "admin")
    @PostMapping("/{orderNo}/audit")
    public Result<Void> audit(@PathVariable String orderNo,
                              @RequestParam(value = "remark", required = false) String remark) {
        adminOrderService.audit(orderNo, remark, StpAdminUtil.getLoginIdAsLong());
        return Result.success("审核通过", null);
    }

    @Operation(summary = "驳回回收单", description = "待审核 → 已驳回；必须填写驳回原因")
    @Log(module = "订单管理", operation = "驳回回收单")
    @SaCheckPermission(value = "order:recycle:reject", type = "admin")
    @PostMapping("/{orderNo}/reject")
    public Result<Void> reject(@PathVariable String orderNo,
                               @RequestParam("reason") String reason) {
        adminOrderService.reject(orderNo, reason, StpAdminUtil.getLoginIdAsLong());
        return Result.success("已驳回", null);
    }

    @Operation(summary = "确认回收并结算",
            description = "录入实际重量并推进订单；settle 为 true（默认）时同时发放积分。积分按订单实际重量计算")
    @Log(module = "订单管理", operation = "确认回收并结算")
    @SaCheckPermission(value = "order:recycle:confirm", type = "admin")
    @PostMapping("/{orderNo}/confirm-recycle")
    public Result<RecycleSettlementVO> confirmRecycle(@PathVariable String orderNo,
                                                      @RequestBody @Valid ConfirmRecycleDTO dto) {
        RecycleSettlementVO result = adminOrderService.confirmRecycle(
                orderNo, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.success("操作成功", result);
    }

    @Operation(summary = "单独结算回收单",
            description = "用于「先确认收货、后结算」的两步流程；订单必须已录入实际重量")
    @Log(module = "订单管理", operation = "结算回收单")
    @SaCheckPermission(value = "order:recycle:confirm", type = "admin")
    @PostMapping("/{orderNo}/settle")
    public Result<RecycleSettlementVO> settle(@PathVariable String orderNo,
                                              @RequestParam(value = "remark", required = false) String remark) {
        RecycleSettlementVO result = adminOrderService.settle(
                orderNo, StpAdminUtil.getLoginIdAsLong(), remark);
        return Result.success("结算完成", result);
    }

    // ==================== 采购单 ====================

    @Operation(summary = "采购单发货", description = "待发货 → 待收货；可回写快递单号，便于用户查询物流")
    @Log(module = "订单管理", operation = "采购单发货")
    @SaCheckPermission(value = "order:purchase:deliver", type = "admin")
    @PostMapping("/{orderNo}/deliver")
    public Result<Void> deliver(@PathVariable String orderNo,
                                @RequestParam(value = "courierNo", required = false) String courierNo) {
        // 发货是采购单的履约动作，因此实现在 PurchaseOrderService 里；
        // 本控制器只是把它暴露到管理端路径下并加上权限校验
        purchaseOrderService.deliver(orderNo, courierNo, StpAdminUtil.getLoginIdAsLong());
        return Result.success("已发货", null);
    }

    /**
     * 导出订单（Excel）。
     *
     * <p><b>返回的是下载链接，不是文件流。</b>原因：微信云托管对返回包有 1000KiB
     * 硬限制，直接往外吐 xlsx 会被截断，前端拿到一个"文件已损坏"的文件。
     * 现在的流程是「生成 → 存对象存储 → 返回链接」，响应只有几十字节。
     *
     * <p>筛选条件与 {@code page} 完全一致（同一个 query 对象、同一套动态 SQL），
     * 保证「列表里看到什么，导出就是什么」。
     *
     * <p><b>权限为什么用 OR</b>：这一个接口同时导出回收单与采购单。
     * 只负责回收单的人（只有 {@code order:recycle:export}）也应该能导出，
     * 用 AND 会让他一个都导不了。
     */
    @Operation(summary = "导出订单",
            description = "按当前筛选条件导出 Excel（上限 1 万条），返回可下载的文件地址")
    @Log(module = "订单管理", operation = "导出订单")
    @SaCheckPermission(value = {"order:recycle:export", "order:purchase:export"},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/export")
    public Result<String> export(AdminOrderQuery query) {
        return Result.success("导出成功", exportService.exportOrders(query));
    }
}
