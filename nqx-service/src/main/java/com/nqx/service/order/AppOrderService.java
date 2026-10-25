package com.nqx.service.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.mapper.OrderFlowMapper;
import com.nqx.mapper.OrderItemMapper;
import com.nqx.mapper.ProductReviewMapper;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.OrderFlow;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.entity.ProductReview;
import com.nqx.pojo.enums.OrderLegacyStatus;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.AppOrderVO;
import com.nqx.service.satoken.StpAppUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 小程序端订单统一服务。
 *
 * <p>回收单与采购单共用一张主表，但对外的「我的订单」列表应当能一次取到两类订单 ——
 * 用户在订单页看到的是"我的所有订单"，不会先去想"这是回收还是采购"。
 * 本类承担这个统一视图，并按订单类型把写操作分发到对应的业务服务。
 *
 * <p><b>为什么取消操作要分发而不是各写一个接口？</b>
 * 因为两类订单的取消规则不同（回收单在"待回收"时仍可取消、采购单一旦支付就不能取消），
 * 这些差异全部封装在 {@code OrderStateMachine} 里。对外只暴露一个"取消订单"，
 * 前端不需要知道当前这单属于哪套状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppOrderService {

    /**
     * 买家两次「提醒发货」之间的最小间隔（小时）。
     *
     * <p>不限频的话，买家着急时会连点几十下，既无意义，
     * 又把后台的"催发货"信号淹没了 —— 管理员看到的全是同一单。
     *
     * <p>做成常量而不是配置项：这个值改动频率极低，
     * 加一个配置项反而多一处需要同步的地方（yml、环境变量、文档）。
     */
    private static final int REMIND_SHIP_INTERVAL_HOURS = 24;

    private final BizOrderMapper bizOrderMapper;
    private final OrderFlowMapper orderFlowMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductReviewMapper productReviewMapper;
    private final RecycleOrderService recycleOrderService;
    private final PurchaseOrderService purchaseOrderService;

    /**
     * 我的订单（跨类型）。
     *
     * @param orderType 订单类型，传 null 表示查看全部
     * @param status    状态筛选。**同时接受数字码与旧版字符串**：
     *                  新客户端传 {@code "13"}，旧小程序按 tab 传
     *                  {@code "pending_shipment"}；传 {@code "all"} 或空表示不过滤。
     *                  解析逻辑见 {@link OrderLegacyStatus#toCodes}。
     */
    public PageResult<AppOrderVO> page(Integer orderType, String status, long pageNum, long pageSize) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        Page<BizOrder> page = new Page<>(
                pageNum < 1 ? CommonConstants.PAGE_NUM_DEFAULT : pageNum,
                pageSize < 1 ? CommonConstants.PAGE_SIZE_DEFAULT
                        : Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX));

        /*
         * 状态筛选：旧小程序是按 tab 传**字符串**的
         * （pending_payment / pending_shipment / …），
         * 而新状态机是数字码。这里做一次解析，返回 null 表示"不过滤"。
         *
         * 为什么参数类型是 String 而不是 Integer：
         * 如果声明成 Integer，旧页面传字符串时 Spring 会在进入方法**之前**
         * 就抛 MethodArgumentTypeMismatchException，用户看到"系统繁忙"。
         * 把兼容逻辑收进方法内部，才能给出"当全部处理"这种合理降级。
         */
        List<Integer> statusCodes = OrderLegacyStatus.toCodes(status, orderType);

        Page<BizOrder> result = bizOrderMapper.selectPage(page, Wrappers.<BizOrder>lambdaQuery()
                .eq(BizOrder::getUserId, userId)
                .eq(orderType != null, BizOrder::getOrderType, orderType)
                // 条件为 null 时 MP 会自动跳过这个 in，等价于不过滤
                .in(statusCodes != null && !statusCodes.isEmpty(), BizOrder::getStatus, statusCodes)
                // 与 idx_user_type_status_time 的最左前缀一致：user_id → order_type → status → create_time
                .orderByDesc(BizOrder::getCreateTime)
                .orderByDesc(BizOrder::getId));

        // 批量装配订单明细：一次查出这一页所有订单的明细再按 orderId 分组，
        // 而不是在 map 里逐单查库（那样一页 10 单就是 10 次往返，即 N+1）
        Map<Long, List<OrderItem>> itemsByOrder = loadItems(result.getRecords());
        // 同理批量装配"是否已评价"，同样一次 IN 查完，不要逐单查
        Set<Long> reviewedOrderIds = loadReviewedOrderIds(result.getRecords());
        return PageUtil.of(result, order -> {
            AppOrderVO vo = AppOrderVO.from(order, itemsByOrder.get(order.getId()));
            vo.setReviewed(reviewedOrderIds.contains(order.getId()));
            return vo;
        });
    }

    /**
     * 批量查出这一页订单里"已经评价过"的订单 id。
     *
     * <h3>为什么需要这个</h3>
     *
     * <p>采购订单列表的「去评价」按钮原本判断
     * {@code item.status === 'pending_evaluation'}，但后端订单完成后
     * 返回的 status 是 {@code 'completed'} —— 两边对不上，
     * <b>按钮永远不出现，评价功能实际不可达</b>。
     *
     * <p>修正时不能只是把条件换成 completed：已评价过的订单会继续显示
     * 「去评价」，用户重复提交会撞 {@code uk_order_product} 唯一索引，
     * 拿到一句看不懂的错误。所以这里把"评价过没有"作为事实下发给前端。
     *
     * <h3>为什么返回 Set 而不是 List</h3>
     *
     * <p>调用方只做 {@code contains} 成员判断。Set 是 O(1)，
     * 而在一页 10 条订单上做 List 的线性查找纯属浪费 ——
     * 更重要的是，<b>返回类型应该表达"这是个集合成员判断"的意图</b>。
     *
     * <p>只查采购单：回收单没有评价概念，白查一遍没有意义。
     */
    private Set<Long> loadReviewedOrderIds(List<BizOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> purchaseOrderIds = orders.stream()
                .filter(o -> OrderTypeEnum.PURCHASE.getCode().equals(o.getOrderType()))
                .map(BizOrder::getId)
                .toList();
        if (purchaseOrderIds.isEmpty()) {
            return Collections.emptySet();
        }
        return productReviewMapper.selectList(Wrappers.<ProductReview>lambdaQuery()
                        .select(ProductReview::getOrderId)
                        .in(ProductReview::getOrderId, purchaseOrderIds))
                .stream()
                .map(ProductReview::getOrderId)
                .collect(Collectors.toSet());
    }

    public AppOrderVO detail(String idOrOrderNo) {
        BizOrder order = findOwnOrder(idOrOrderNo);
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId()));
        AppOrderVO vo = AppOrderVO.from(order, items);
        // 详情页同样要带"是否已评价"，否则从详情页判断不出该不该显示「去评价」
        vo.setReviewed(loadReviewedOrderIds(List.of(order)).contains(order.getId()));
        return vo;
    }

    /**
     * 批量加载订单明细并按 orderId 分组。
     *
     * <p>用 {@code in} 一次查完，而不是在循环里逐单查 ——
     * 后者在"我的订单"页（默认 10 条/页）就是 10 次额外往返，
     * 而这条路径是用户最常打开的页面之一。
     */
    private Map<Long, List<OrderItem>> loadItems(List<BizOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> orderIds = orders.stream().map(BizOrder::getId).toList();
        return orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    /** 流转进度（时间轴） */
    public List<OrderFlow> flowList(String idOrOrderNo) {
        BizOrder order = findOwnOrder(idOrOrderNo);
        return orderFlowMapper.selectList(Wrappers.<OrderFlow>lambdaQuery()
                .eq(OrderFlow::getOrderId, order.getId())
                .orderByAsc(OrderFlow::getCreateTime)
                .orderByAsc(OrderFlow::getId));
    }

    /** 取消订单：按订单类型分发到对应的状态机 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String idOrOrderNo, String reason) {
        BizOrder order = findOwnOrder(idOrOrderNo);
        OrderTypeEnum type = OrderTypeEnum.of(order.getOrderType());
        if (type == null) {
            throw new BizException(ResultCode.ORDER_TYPE_INVALID);
        }
        if (type == OrderTypeEnum.RECYCLE) {
            recycleOrderService.cancel(idOrOrderNo, reason);
        } else {
            purchaseOrderService.cancel(idOrOrderNo, reason);
        }
    }

    /**
     * 买家「提醒发货」。
     *
     * <h2>为什么买家不能自己发货</h2>
     *
     * <p>旧版这个按钮直接调「发货」接口 —— 买家点一下就能把自己的订单标记成已发货，
     * 物流信息完全是编的。这是**数据造假**级别的问题，已经修正为「发货只能管理员操作」。
     *
     * <p>但"什么都不做"同样不对：买家的催发货诉求是真实的，而管理员完全感知不到。
     * 所以这里把它做成**只读的催促信号**：买家能表达诉求，但改不了订单状态。
     *
     * <h2>限频为什么是 24 小时</h2>
     *
     * <p>不限频的话，买家着急时可能连点几十下，既没意义又把后台的"催发货"信号淹没了。
     * 24 小时一次是个平衡：一天催一次是合理的，再频繁就是骚扰。
     *
     * <p>限频判断放在**业务层**而不是数据库唯一约束里：规则随时可能调整
     * （比如活动期间改成 6 小时），改代码比改表结构容易。
     *
     * @return 更新后的订单（前端拿它直接刷新卡片，不用再发一次详情请求）
     */
    @Transactional(rollbackFor = Exception.class)
    public AppOrderVO remindShip(String idOrOrderNo) {
        BizOrder order = findOwnOrder(idOrOrderNo);

        if (!OrderTypeEnum.PURCHASE.getCode().equals(order.getOrderType())) {
            throw new BizException(ResultCode.PARAM_ERROR, "只有采购订单可以催发货");
        }
        if (!OrderStatusEnum.PAID.getCode().equals(order.getStatus())) {
            throw new BizException(ResultCode.PARAM_ERROR, "当前订单不是待发货状态，无需提醒");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = order.getRemindShipAt();
        if (last != null && last.plusHours(REMIND_SHIP_INTERVAL_HOURS).isAfter(now)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "今天已经提醒过了，工作人员会尽快处理");
        }

        int nextCount = (order.getRemindShipCount() == null ? 0 : order.getRemindShipCount()) + 1;

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setRemindShipAt(now);
        update.setRemindShipCount(nextCount);
        bizOrderMapper.updateById(update);

        log.info("买家提醒发货 | orderNo={} | 累计={}次 | 距上次={}",
                order.getOrderNo(), nextCount, last == null ? "从未" : last.toString());

        // 直接回填到入参对象，避免再查一次数据库
        order.setRemindShipAt(now);
        order.setRemindShipCount(nextCount);
        return AppOrderVO.from(order);
    }

    /**
     * 查找当前用户的订单。
     *
     * <p>复用 {@code RecycleOrderService} 的包级方法，而不是各写一份 ——
     * 归属校验是防水平越权的关键一步，绝不能有第二份实现（迟早会漏）。
     */
    private BizOrder findOwnOrder(String idOrOrderNo) {
        return recycleOrderService.findOwnOrder(idOrOrderNo, StpAppUtil.getLoginIdAsLong());
    }
}
