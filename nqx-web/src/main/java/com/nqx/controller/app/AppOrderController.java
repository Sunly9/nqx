package com.nqx.controller.app;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.RecycleOrderCreateDTO;
import com.nqx.pojo.dto.order.ExchangeOrderCreateDTO;
import com.nqx.pojo.dto.order.PurchaseOrderCreateDTO;
import com.nqx.pojo.entity.OrderFlow;
import com.nqx.pojo.vo.app.AppOrderVO;
import com.nqx.service.order.AppOrderService;
import com.nqx.service.order.PurchaseOrderService;
import com.nqx.service.order.RecycleOrderService;
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

import java.util.List;

/**
 * 小程序端订单接口（回收单 + 采购单）。
 *
 * <p>全部需要登录；用户身份一律从 Sa-Token 会话取，
 * <b>不接受请求参数里的 user_id</b>。
 */
@Tag(name = "小程序端-订单", description = "回收单与采购单的提交、支付、取消、进度查询")
@RestController
@RequestMapping("/app/order")
@RequiredArgsConstructor
public class AppOrderController {

    private final AppOrderService appOrderService;
    private final RecycleOrderService recycleOrderService;
    private final PurchaseOrderService purchaseOrderService;

    // ==================== 回收单 ====================

    @Operation(summary = "提交回收单",
            description = "按重量计价；此时不发放积分，需管理员现场称重确认后按实际重量结算")
    @PostMapping("/recycle")
    public Result<AppOrderVO> createRecycleOrder(@RequestBody @Valid RecycleOrderCreateDTO dto) {
        return Result.success("提交成功", recycleOrderService.create(dto));
    }

    // ==================== 采购单 ====================

    @Operation(summary = "提交采购单",
            description = "source=cart 结算购物车（默认）或 source=direct 立即购买；下单即扣库存")
    @PostMapping("/purchase")
    public Result<AppOrderVO> createPurchaseOrder(@RequestBody @Valid PurchaseOrderCreateDTO dto) {
        return Result.success("下单成功", purchaseOrderService.create(dto));
    }

    @Operation(summary = "模拟支付",
            description = "本项目不接真实微信支付：直接标记为已支付并记录支付时间")
    @PostMapping("/{orderNo}/pay")
    public Result<Void> pay(@PathVariable String orderNo) {
        purchaseOrderService.pay(orderNo);
        return Result.success("支付成功", null);
    }

    @Operation(summary = "确认收货", description = "确认后订单完成并按消费金额发放积分")
    @PostMapping("/{orderNo}/confirm")
    public Result<Void> confirmReceipt(@PathVariable String orderNo) {
        purchaseOrderService.confirmReceipt(orderNo);
        return Result.success("已确认收货", null);
    }

    @Operation(summary = "积分兑换下单",
            description = "用积分兑换商品；下单时即时扣减积分与库存，订单直接进入「待发货」")
    @PostMapping("/exchange")
    public Result<AppOrderVO> exchange(@RequestBody @Valid ExchangeOrderCreateDTO dto) {
        return Result.success("兑换成功", purchaseOrderService.exchange(dto));
    }

    // ==================== 两类订单通用 ====================

    @Operation(summary = "我的订单",
            description = "orderType 不传表示查看全部；status 同时接受数字码（如 41）与旧版字符串（如 pending_shipment），"
                    + "传 all 或空表示不过滤")
    @GetMapping("/page")
    public Result<PageResult<AppOrderVO>> page(
            @RequestParam(value = "orderType", required = false) Integer orderType,
            /*
             * status 声明为 String 而不是 Integer 是有意的。
             *
             * 旧小程序按 tab 切换时传的是**旧版字符串**（pending_payment / pending_shipment…）。
             * 若这里声明成 Integer，Spring 会在进入方法前就抛
             * MethodArgumentTypeMismatchException，用户看到"系统繁忙" ——
             * 而这本可以通过容错解析给出合理结果（未知取值当"全部"处理）。
             *
             * 参数类型的选择也是兼容设计的一部分：**在边界处宽容，
             * 比在业务代码里到处补判断更好。**
             */
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.success(appOrderService.page(orderType, status, pageNum, pageSize));
    }

    @Operation(summary = "订单详情",
            description = "路径参数支持主键 ID（兼容旧前端）与订单号两种形式")
    @GetMapping("/{idOrOrderNo}")
    public Result<AppOrderVO> detail(@PathVariable String idOrOrderNo) {
        return Result.success(appOrderService.detail(idOrOrderNo));
    }

    @Operation(summary = "订单流转进度", description = "按时间正序返回，可直接渲染为时间轴")
    @GetMapping("/{idOrOrderNo}/flow")
    public Result<List<OrderFlow>> flow(@PathVariable String idOrOrderNo) {
        return Result.success(appOrderService.flowList(idOrOrderNo));
    }

    @Operation(summary = "取消订单",
            description = "是否允许取消由订单状态机按类型判定：采购单一旦支付即不可取消")
    @PostMapping("/{idOrOrderNo}/cancel")
    public Result<Void> cancel(@PathVariable String idOrOrderNo,
                               @RequestParam(value = "reason", required = false) String reason) {
        appOrderService.cancel(idOrOrderNo, reason);
        return Result.success("已取消", null);
    }

    /**
     * 买家「提醒发货」。
     *
     * <p><b>为什么这不是「发货」而是「提醒」</b>：旧版这个入口直接调发货接口，
     * 等于买家自己把订单标记成已发货 —— 物流信息全是编的。发货是管理端动作
     * （{@code POST /admin/order/{orderNo}/deliver}，权限码 {@code order:purchase:deliver}），
     * 买家只能表达"我等着呢"，改不了状态。
     *
     * <p>限频 24 小时一次，超了会返回明确的业务错误（而不是静默成功）——
     * 让用户知道"已经提醒过了"，而不是以为没点上。
     *
     * @return 更新后的订单，前端拿它直接刷新卡片，省一次详情请求
     */
    @Operation(summary = "提醒发货",
            description = "仅采购单且处于待发货状态可提醒；同一订单 24 小时内只能提醒一次")
    @PostMapping("/{idOrOrderNo}/remind-ship")
    public Result<AppOrderVO> remindShip(@PathVariable String idOrOrderNo) {
        return Result.success("已提醒商家发货", appOrderService.remindShip(idOrOrderNo));
    }
}
