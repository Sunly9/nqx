package com.nqx.service.order;

import cn.hutool.json.JSONUtil;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.mapper.OrderItemMapper;
import com.nqx.mapper.ProductMapper;
import com.nqx.pojo.dto.order.ExchangeOrderCreateDTO;
import com.nqx.pojo.dto.order.PurchaseOrderCreateDTO;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.Cart;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.entity.Product;
import com.nqx.pojo.entity.UserAddress;
import com.nqx.pojo.enums.OrderActionEnum;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import com.nqx.pojo.vo.app.AppOrderVO;
import com.nqx.service.app.CartService;
import com.nqx.service.app.UserAddressService;
import com.nqx.service.content.ContentService;
import com.nqx.service.points.PointsService;
import com.nqx.service.product.ProductService;
import com.nqx.service.satoken.StpAppUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采购单服务。
 *
 * <p>与回收单共用 {@code biz_order} 主表和 {@code order_flow} 流转日志，
 * 但状态机、履约流程、超时规则完全不同：
 * <ul>
 *   <li>回收单：提交 → 审核 → 上门/寄送 → 称重结算发积分（人工驱动为主）</li>
 *   <li>采购单：提交 → 支付 → 发货 → 收货发积分（有超时自动关闭）</li>
 * </ul>
 * 差异由 {@code OrderStateMachine} 按订单类型分发，业务代码里不出现
 * {@code if (orderType == 1)} 这类分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    /** 下单来源：购物车结算 */
    private static final String SOURCE_CART = "cart";

    /** 支付方式：积分支付 */
    private static final int PAY_TYPE_POINTS = 1;

    /** 支付方式：模拟结账（本项目不接真实微信支付） */
    private static final int PAY_TYPE_MOCK = 2;

    /** 商品类型：仅采购 */
    private static final int PRODUCT_TYPE_PURCHASE = 1;

    /** 订单号冲突重试次数 */
    private static final int MAX_ORDER_NO_RETRY = 3;

    private final BizOrderMapper bizOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    /** 库存变更统一走 ProductService —— 它会同时失效商品详情缓存 */
    private final ProductService productService;
    private final CartService cartService;
    private final UserAddressService userAddressService;
    private final OrderStateMachine orderStateMachine;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderDelayQueue orderDelayQueue;
    private final RecycleOrderService orderHelper;
    private final PointsService pointsService;
    private final ContentService contentService;

    /**
     * 未支付自动关闭时长（分钟）。
     *
     * <p>注入到 Service 而不是在 Task 里读，是为了保证"投递延迟任务"与
     * "补偿任务判定超时"用的是同一个值 —— 两处若各读一次配置，
     * 改配置时就可能出现两个值不一致的窗口。
     */
    @Value("${nqx.order.timeout-minutes:30}")
    private int timeoutMinutes;

    // ==================== 下单 ====================

    /**
     * 提交采购单。
     *
     * <p>整个过程在一个事务里：校验地址 → 解析明细 → <b>扣库存</b> →
     * 写订单/明细/流转日志 → 清购物车。任何一步失败全部回滚，
     * 不会出现"库存扣了但订单没生成"这种最难排查的状态。
     *
     * <p>注意延迟任务是在事务<b>提交之后</b>才投递的（见方法末尾的说明），
     * 不是随事务一起回滚 —— 这点务必理解清楚，否则会出现"订单已回滚、
     * 但延迟任务还在，到点后去关一个不存在的订单"。
     */
    @Transactional(rollbackFor = Exception.class)
    public AppOrderVO create(PurchaseOrderCreateDTO dto) {
        Long userId = StpAppUtil.getLoginIdAsLong();

        // 1. 地址快照：新版传 addressId（校验归属后取地址簿），
        //    旧版把地址内容内联在请求体里 —— 两者都支持，但都不落 user_address 表。
        String fullAddress;
        String contactName;
        String contactPhone;
        Long addressId = null;
        if (dto.getAddressId() != null) {
            UserAddress address = userAddressService.requireOwned(userId, dto.getAddressId());
            fullAddress = userAddressService.formatFullAddress(address);
            contactName = address.getReceiverName();
            contactPhone = address.getReceiverPhone();
            addressId = address.getId();
        } else if (dto.hasInlineAddress()) {
            // 旧版下单页直接提交 { name, phone, address }。
            // 只作为本单的快照使用，不往用户地址簿里写 —— 旧客户端没有"保存地址"
            // 的意图，替它做主会在地址簿里堆出一堆重复条目。
            fullAddress = dto.getAddress().getAddress();
            contactName = dto.getAddress().getName();
            contactPhone = dto.getAddress().getPhone();
        } else {
            throw new BizException(ResultCode.PURCHASE_ADDRESS_REQUIRED, "请先选择收货地址");
        }

        // 2. 解析明细（旧版商品数组 / 购物车结算 / 立即购买）
        List<OrderItemDraft> drafts = resolveDrafts(userId, dto);

        // 3. 扣库存 —— 防超卖的关键一步，放在写订单之前。
        //    先扣库存再写单：库存不足时能立刻中止，不产生任何订单数据。
        //    若反过来先写单再扣库存，一旦扣减失败就要靠回滚撤销订单，
        //    虽然事务能保证正确，但会产生无意义的自增 ID 消耗与日志噪音。
        for (OrderItemDraft draft : drafts) {
            // 走 ProductService 而不是直接调 Mapper：
            // 库存变更必须顺带失效商品详情缓存，这件事封在 ProductService 里，
            // 调用方不可能忘（详见 ProductService#deductStock 的说明）
            int affected = productService.deductStock(draft.productId(), draft.quantity());
            if (affected == 0) {
                // 提示里带上商品名，用户才知道是哪一个没货了
                throw new BizException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH,
                        "「" + draft.name() + "」库存不足");
            }
        }

        // 4. 汇总金额与积分
        int totalQuantity = drafts.stream().mapToInt(OrderItemDraft::quantity).sum();
        int totalAmount = drafts.stream().mapToInt(OrderItemDraft::subtotalAmount).sum();

        // 5. 写订单主表
        BizOrder order = new BizOrder();
        order.setUserId(userId);
        order.setOrderType(OrderTypeEnum.PURCHASE.getCode());
        order.setStatus(orderStateMachine.initialStatus(OrderTypeEnum.PURCHASE, false).getCode());
        order.setTotalQuantity(totalQuantity);
        order.setTotalAmount(totalAmount);
        order.setTotalPoints(0);
        // addressId 可能为 null（旧版内联地址没有地址簿记录），
        // 这不影响履约：订单本身的联系人/电话/地址文本才是发货依据
        order.setAddressId(addressId);
        order.setContactName(contactName);
        order.setContactPhone(contactPhone);
        order.setAddressDetail(fullAddress);
        order.setUserRemark(dto.getRemark());
        order.setVersion(0);
        order.setDeleted(CommonConstants.DELETED_NO);
        insertWithUniqueOrderNo(order);

        // 6. 写订单明细（含商品快照）
        for (OrderItemDraft draft : drafts) {
            orderItemMapper.insert(draft.toEntity(order));
        }

        // 7. 写流转日志
        orderHelper.writeFlow(order, null, order.getStatus(), OrderActionEnum.SUBMIT,
                userId, CommonConstants.OPERATOR_TYPE_USER, "用户提交采购单");

        // 8. 只有「购物车结算」才清购物车。
        //    旧版请求带 products 数组时商品来自本地选中列表，与购物车无关；
        //    立即购买同理。若不加这个判断，旧版下单会把购物车里恰好同款的商品误删。
        if (!dto.isDirect() && !dto.hasInlineProducts()) {
            cartService.removeByProductIds(userId,
                    drafts.stream().map(OrderItemDraft::productId).toList());
        }

        // 9. 投递延迟关闭任务。
        //    用 TransactionSynchronization 注册"提交成功后执行"，
        //    而不是直接 push —— 若事务最终回滚，延迟任务也已经写进 Redis 了，
        //    到点后任务会去处理一个根本不存在的订单（虽然会被"查不到订单"挡掉，
        //    但白白产生噪音）。等提交成功再投递才是准确的。
        Long orderId = order.getId();
        String orderNo = order.getOrderNo();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        orderDelayQueue.push(orderNo, timeoutMinutes * 60L);
                        log.info("采购单已提交 | orderNo={} | userId={} | 金额={}分 | {}分钟后未支付将自动关闭",
                                orderNo, userId, totalAmount, timeoutMinutes);
                    }
                });

        return AppOrderVO.from(order);
    }

    // ==================== 支付（模拟结账） ====================

    /**
     * 模拟支付。
     *
     * <p>本项目不接入真实微信支付（需求已明确），这里是"虚假结账"：
     * 直接改状态并记录支付方式与时间。真实支付需要额外处理
     * 统一下单、支付回调签名校验、回调幂等、对账 —— 接口形态已预留
     * （{@code pay_type} / {@code pay_time} 字段与 PayService 的扩展点）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pay(String orderNo) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = orderHelper.findOwnOrder(orderNo, userId);

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.PURCHASE, current, OrderActionEnum.PAY);

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        update.setPayType(PAY_TYPE_MOCK);
        update.setPayTime(LocalDateTime.now());
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        orderHelper.writeFlow(order, current, target.getCode(), OrderActionEnum.PAY,
                userId, CommonConstants.OPERATOR_TYPE_USER, "模拟支付成功（本项目不接真实支付）");

        // 刻意不从延迟队列移除：支付事务若在后续步骤回滚，
        // 移除后这单就永远不会被超时关闭，会永久卡在"待支付"。
        // 让任务留在队列里、由处理方先查状态再决定，用一次多余查询换确定性。
        log.info("采购单支付成功 | orderNo={} | 金额={}分", orderNo, order.getTotalAmount());
    }

    // ==================== 取消 / 确认收货 ====================

    /** 用户取消（仅待支付状态允许，由状态机判定） */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, String reason) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = orderHelper.findOwnOrder(orderNo, userId);

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.PURCHASE, current, OrderActionEnum.CANCEL);

        restoreStock(order);
        markCancelled(order, current, target, userId, CommonConstants.OPERATOR_TYPE_USER, reason);
        log.info("用户取消采购单 | orderNo={} | 原因={}", orderNo, reason);
    }

    /**
     * 超时未支付自动关闭。
     *
     * @return true 表示本次调用确实关闭了订单；false 表示订单已支付/已取消/不存在，
     *         属于正常的竞态（延迟消息到点时订单状态已变），不应视为错误
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeIfUnpaid(String orderNo) {
        BizOrder order = bizOrderMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizOrder>lambdaQuery()
                        .eq(BizOrder::getOrderNo, orderNo));
        if (order == null) {
            // 订单已被删除（人工清理）或本就不存在，直接忽略
            log.warn("超时关闭任务找不到订单，已忽略 | orderNo={}", orderNo);
            return false;
        }

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        if (current != OrderStatusEnum.PENDING_PAY) {
            // 这是延迟队列的正常竞态：任务投递后用户又完成了支付/取消。
            // 必须在这里做状态校验，否则已支付的订单会被错误关闭。
            log.info("订单已不是待支付状态，跳过超时关闭 | orderNo={} | 当前状态={}",
                    orderNo, current == null ? "未知" : current.getDesc());
            return false;
        }

        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.PURCHASE, current, OrderActionEnum.TIMEOUT_CLOSE);

        restoreStock(order);
        markCancelled(order, current, target, null, CommonConstants.OPERATOR_TYPE_SYSTEM,
                String.format("超过 %d 分钟未支付，系统自动关闭", timeoutMinutes));

        log.info("采购单超时自动关闭 | orderNo={} | 已回滚库存", orderNo);
        return true;
    }

    /** 用户确认收货，并发放采购积分 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(String orderNo) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = orderHelper.findOwnOrder(orderNo, userId);

        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.PURCHASE, current, OrderActionEnum.CONFIRM_RECEIPT);

        // 采购积分：每消费 1 元得 N 积分（N 来自站点配置）
        int pointsPerYuan = contentService.getIntSetting("purchase_points_per_yuan", 1);
        int totalAmount = order.getTotalAmount() == null ? 0 : order.getTotalAmount();
        // 金额单位是分，先除以 100 得到元再乘系数；整体向下取整，宁可少给不多给
        int points = (totalAmount / 100) * pointsPerYuan;

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        update.setTotalPoints(points);
        update.setFinishTime(LocalDateTime.now());
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        orderHelper.writeFlow(order, current, target.getCode(), OrderActionEnum.CONFIRM_RECEIPT,
                userId, CommonConstants.OPERATOR_TYPE_USER, "用户确认收货");

        if (points > 0) {
            // 幂等键用订单号：重复确认收货不会重复发积分
            // （状态机是第一道防线，points_record 的唯一索引是第二道）
            pointsService.reward(userId, points, PointsChangeTypeEnum.PURCHASE_REWARD,
                    order.getOrderNo(), order.getId(), "采购订单完成奖励");
        }
        log.info("采购单确认收货 | orderNo={} | 发放积分={}", orderNo, points);
    }

    /**
     * 管理员发货，可回写快递单号。
     *
     * <p>{@code courierNo} 是可选的：现实里经常是"先点发货、拿到面单后再补单号"，
     * 强制必填会逼着运营先跑一趟快递再回来操作，反而降低效率。
     * 留空时用户在前端看到的是"已发货（暂无物流信息）"。
     *
     * <p>注意 {@code biz_order.courier_no} 这一列在两个业务里语义不同：
     * 回收单存的是<b>用户寄给我们</b>的单号，采购单存的是<b>我们寄给用户</b>的单号。
     * 复用同一列是因为两者不会同时存在于一张单上（订单类型互斥），
     * 语义差异在各自的展示逻辑里体现。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deliver(String orderNo, String courierNo, Long adminId) {
        BizOrder order = orderHelper.findByIdOrOrderNo(orderNo);
        if (!OrderTypeEnum.PURCHASE.getCode().equals(order.getOrderType())) {
            throw new BizException(ResultCode.ORDER_TYPE_INVALID, "该订单不是采购单");
        }
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.PURCHASE, current, OrderActionEnum.DELIVER);

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        if (StringUtils.hasText(courierNo)) {
            update.setCourierNo(courierNo.trim());
        }
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        orderHelper.writeFlow(order, current, target.getCode(), OrderActionEnum.DELIVER,
                adminId, CommonConstants.OPERATOR_TYPE_ADMIN,
                StringUtils.hasText(courierNo) ? "管理员发货，快递单号 " + courierNo : "管理员发货");
        log.info("采购单已发货 | orderNo={} | courierNo={} | adminId={}", orderNo,
                StringUtils.hasText(courierNo) ? courierNo : "（未填写）", adminId);
    }

    // ==================== 积分兑换 ====================

    /**
     * 积分兑换下单。
     *
     * <p><b>为什么兑换复用了采购单的这一套代码？</b>
     * 因为两者的履约流程完全一致（待发货 → 发货 → 收货），
     * 差别只在"用什么支付"：采购单付现金，兑换付积分。
     * 兑换的积分在下单时即时扣减，所以订单直接进入「待发货」，
     * 跳过了"待支付"这一状态 —— 这也是状态机为两种支付方式
     * 预留 {@code initialStatus(type, paidByPoints)} 参数的原因。
     *
     * <p><b>执行顺序很关键</b>：先插订单拿到订单号，再用它作为积分扣减的幂等键。
     * 反过来做的话，"先生成订单号 → 扣积分 → 插订单"这条链路上，
     * 一旦订单号撞车（{@code insertWithUniqueOrderNo} 会重试并换号），
     * 积分流水记的就会是一个并不存在的订单号，对账时无从追溯。
     *
     * <p>整个过程在一个事务里：库存不足或积分不足都会让订单一起回滚，
     * 不会出现"积分扣了但没生成订单"或"订单生成了但没扣积分"。
     */
    @Transactional(rollbackFor = Exception.class)
    public AppOrderVO exchange(ExchangeOrderCreateDTO dto) {
        Long userId = StpAppUtil.getLoginIdAsLong();

        // 地址：传了就校验归属，没传就取默认地址。
        // 旧版兑换页没有选地址这一步，因此这里必须能兜底，
        // 否则那个页面的兑换功能会直接不可用。
        UserAddress address = dto.getAddressId() != null
                ? userAddressService.requireOwned(userId, dto.getAddressId())
                : userAddressService.findDefaultOrLatest(userId);
        if (address == null) {
            throw new BizException(ResultCode.ADDRESS_NOT_FOUND, "请先添加收货地址后再兑换");
        }
        String fullAddress = userAddressService.formatFullAddress(address);

        Product product = requireExchangeable(dto.getProductId());
        int quantity = dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();

        // 每人限兑校验（exchange_limit = 0 表示不限）
        checkExchangeLimit(userId, product, quantity);

        int pointsPrice = product.getPointsPrice() == null ? 0 : product.getPointsPrice();
        int pointsNeeded = pointsPrice * quantity;

        // ---------- 1. 先插订单，拿到稳定的订单号作为幂等键 ----------
        BizOrder order = new BizOrder();
        order.setUserId(userId);
        order.setOrderType(OrderTypeEnum.PURCHASE.getCode());
        // 积分即时扣减，因此直接进入「待发货」
        order.setStatus(orderStateMachine.initialStatus(OrderTypeEnum.PURCHASE, true).getCode());
        order.setTotalQuantity(quantity);
        // 兑换单不动用现金，金额恒为 0；只记消耗的积分
        order.setTotalAmount(0);
        order.setTotalPoints(pointsNeeded);
        order.setPayType(PAY_TYPE_POINTS);
        order.setPayTime(LocalDateTime.now());
        order.setAddressId(address.getId());
        order.setContactName(address.getReceiverName());
        order.setContactPhone(address.getReceiverPhone());
        order.setAddressDetail(fullAddress);
        order.setUserRemark(dto.getRemark());
        order.setVersion(0);
        order.setDeleted(CommonConstants.DELETED_NO);
        insertWithUniqueOrderNo(order);

        // ---------- 2. 扣积分（条件更新，余额不足直接抛异常） ----------
        if (pointsNeeded > 0) {
            pointsService.deduct(userId, pointsNeeded, PointsChangeTypeEnum.EXCHANGE,
                    order.getOrderNo(), order.getId(),
                    "兑换商品：" + product.getName());
        }

        // ---------- 3. 扣库存（防超卖） ----------
        int affected = productService.deductStock(product.getId(), quantity);
        if (affected == 0) {
            throw new BizException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH,
                    "「" + product.getName() + "」库存不足");
        }

        // ---------- 4. 明细与流转日志 ----------
        orderItemMapper.insert(OrderItemDraft.of(product, quantity).toEntity(order));
        orderHelper.writeFlow(order, null, order.getStatus(), OrderActionEnum.PAY,
                userId, CommonConstants.OPERATOR_TYPE_USER,
                String.format("积分兑换，消耗 %d 积分", pointsNeeded));

        // 兑换单已支付，无需投递超时关闭任务
        log.info("积分兑换成功 | orderNo={} | userId={} | 商品={} | 数量={} | 消耗积分={}",
                order.getOrderNo(), userId, product.getName(), quantity, pointsNeeded);

        return AppOrderVO.from(order);
    }

    /**
     * 每人限兑校验。
     *
     * <p>这是典型的"读-判断-写"逻辑，理论上存在并发超额风险
     * （两个请求同时读到已兑 0 件、都认为还能兑 20 件）。
     * 但限兑是<b>运营策略</b>而不是资金安全边界：
     * 即便并发下超额一两件，损失可控；而为此加分布式锁或唯一约束
     * 会让实现复杂很多。因此这里采用简单校验，并在注释里显式说明这个取舍 ——
     * <b>知道自己在哪放松了约束，比假装它不存在重要。</b>
     *
     * <p>真正必须严格的是积分与库存，那两处已经用了条件更新 + 唯一索引。
     */
    private void checkExchangeLimit(Long userId, Product product, int quantity) {
        Integer limit = product.getExchangeLimit();
        if (limit == null || limit <= 0) {
            return;
        }
        Integer exchanged = orderItemMapper.countExchangedQuantity(userId, product.getId());
        int already = exchanged == null ? 0 : exchanged;
        if (already + quantity > limit) {
            throw new BizException(ResultCode.EXCHANGE_LIMIT_EXCEEDED,
                    String.format("该商品每人限兑 %d 件，您已兑换 %d 件", limit, already));
        }
    }

    /** 校验商品支持积分兑换 */
    private Product requireExchangeable(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BizException(ResultCode.PRODUCT_OFF_SHELF);
        }
        // 仅采购的商品（productType=1）不能用积分兑换
        if (Integer.valueOf(PRODUCT_TYPE_PURCHASE).equals(product.getProductType())) {
            throw new BizException(ResultCode.PRODUCT_NOT_SUPPORT_EXCHANGE);
        }
        if (product.getPointsPrice() == null || product.getPointsPrice() <= 0) {
            throw new BizException(ResultCode.PRODUCT_NOT_SUPPORT_EXCHANGE, "该商品未设置积分价");
        }
        return product;
    }

    // ==================== 私有方法 ====================

    /** 回滚订单中所有商品的库存 */
    private void restoreStock(BizOrder order) {
        List<OrderItem> items = orderItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem item : items) {
            // item_type=1 才是商品；回收品类没有库存概念
            if (item.getItemType() != null && item.getItemType() == 1) {
                // 同样走 ProductService，让库存回滚与缓存失效一起发生
                productService.restoreStock(item.getItemId(), item.getQuantity());
            }
        }
    }

    private void markCancelled(BizOrder order, OrderStatusEnum from, OrderStatusEnum to,
                               Long operatorId, int operatorType, String reason) {
        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(to.getCode());
        if (to == OrderStatusEnum.CANCELLED) {
            update.setCancelTime(LocalDateTime.now());
        }
        update.setCancelReason(reason);
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        OrderActionEnum action = to == OrderStatusEnum.CLOSED
                ? OrderActionEnum.TIMEOUT_CLOSE : OrderActionEnum.CANCEL;
        orderHelper.writeFlow(order, from, to.getCode(), action, operatorId, operatorType, reason);
    }

    private List<OrderItemDraft> resolveDrafts(Long userId, PurchaseOrderCreateDTO dto) {
        // 优先级：旧版商品数组 > 立即购买 > 购物车结算
        if (dto.hasInlineProducts()) {
            return fromInlineProducts(dto.getProducts());
        }
        return dto.isDirect() ? fromDirect(dto) : fromCart(userId);
    }

    /**
     * 旧版下单请求：商品数组直接放在 {@code products} 里。
     *
     * <p><b>只认 {@code productId} 与 {@code quantity}，其余一律忽略。</b>
     * 旧请求体里还带着 {@code name} / {@code price} / {@code image}，
     * 但这些都由服务端按商品 ID 从数据库重新取 ——
     * 若采信客户端传来的 {@code price}，用户把它从 1.00 改成 0.01
     * 就能一分钱买走商品。这与旧版结算接口让客户端传 {@code actual_weight}
     * 是同一类漏洞，只是换了个字段。
     *
     * <p>用 {@code selectBatchIds} 一次查完所有商品，避免循环查库。
     */
    private List<OrderItemDraft> fromInlineProducts(List<PurchaseOrderCreateDTO.InlineItem> items) {
        List<Long> productIds = items.stream()
                .map(PurchaseOrderCreateDTO.InlineItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择要购买的商品");
        }

        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a, HashMap::new));

        List<OrderItemDraft> drafts = new ArrayList<>(items.size());
        for (PurchaseOrderCreateDTO.InlineItem item : items) {
            if (item.getProductId() == null) {
                continue;
            }
            Product product = productMap.get(item.getProductId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                throw new BizException(ResultCode.PRODUCT_OFF_SHELF, "所选商品已下架，请重新选择");
            }
            int quantity = (item.getQuantity() == null || item.getQuantity() < 1)
                    ? 1 : item.getQuantity();
            drafts.add(OrderItemDraft.of(product, quantity));
        }
        if (drafts.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择要购买的商品");
        }
        return drafts;
    }

    /** 立即购买 */
    private List<OrderItemDraft> fromDirect(PurchaseOrderCreateDTO dto) {
        if (dto.getProductId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "立即购买时必须指定商品");
        }
        Product product = requirePurchasable(dto.getProductId());
        int quantity = dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();
        return Collections.singletonList(OrderItemDraft.of(product, quantity));
    }

    /**
     * 购物车结算。
     *
     * <p>用 {@code selectBatchIds} 一次查出所有商品，避免在循环里逐个查库（N+1）。
     * 购物车通常只有几项，但 N+1 是习惯问题 —— 一旦某天出现"一键加购 50 件"的功能，
     * 循环查库就会变成 50 次往返。
     */
    private List<OrderItemDraft> fromCart(Long userId) {
        List<Cart> cartItems = cartService.listSelected(userId);
        if (cartItems.isEmpty()) {
            throw new BizException(ResultCode.CART_EMPTY);
        }

        List<Long> productIds = cartItems.stream().map(Cart::getProductId).distinct().toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a, HashMap::new));

        List<OrderItemDraft> drafts = new ArrayList<>(cartItems.size());
        for (Cart item : cartItems) {
            Product product = productMap.get(item.getProductId());
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                // 商品在下单前被下架或删除：明确告知是哪一个，
                // 而不是笼统地报"下单失败"让用户自己猜
                throw new BizException(ResultCode.PRODUCT_OFF_SHELF,
                        "购物车中有商品已下架，请移除后重试");
            }
            drafts.add(OrderItemDraft.of(product, item.getQuantity()));
        }
        return drafts;
    }

    /** 校验商品可采购 */
    private Product requirePurchasable(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BizException(ResultCode.PRODUCT_OFF_SHELF);
        }
        // 仅积分兑换的商品（productType=2）不能用现金购买
        if (Integer.valueOf(2).equals(product.getProductType())) {
            throw new BizException(ResultCode.PRODUCT_NOT_SUPPORT_PURCHASE);
        }
        return product;
    }

    private void insertWithUniqueOrderNo(BizOrder order) {
        for (int attempt = 1; attempt <= MAX_ORDER_NO_RETRY; attempt++) {
            order.setOrderNo(orderNoGenerator.generate(OrderTypeEnum.PURCHASE));
            try {
                bizOrderMapper.insert(order);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.warn("订单号冲突，重试第 {} 次 | orderNo={}", attempt, order.getOrderNo());
            }
        }
        throw new BizException(ResultCode.SYSTEM_ERROR, "订单号生成失败，请重试");
    }

    private Integer nextVersion(BizOrder order) {
        return order.getVersion() == null ? 1 : order.getVersion() + 1;
    }

    /**
     * 订单明细草稿。
     *
     * <p>用 record 而不是直接拼 {@code OrderItem} 实体：
     * 草稿阶段还没有 orderId，实体缺少这个必填字段反而不完整；
     * 而且草稿需要携带商品名用于"库存不足"的报错文案，
     * 这是实体里没有的。用 record 把"下单前的中间态"表达清楚。
     */
    private record OrderItemDraft(Long productId, String name, String spec, String unit, String cover,
                                  Integer price, Integer pointsPrice, Integer quantity,
                                  Integer subtotalAmount, Integer subtotalPoints) {

        static OrderItemDraft of(Product product, int quantity) {
            int price = product.getPrice() == null ? 0 : product.getPrice();
            int pointsPrice = product.getPointsPrice() == null ? 0 : product.getPointsPrice();
            return new OrderItemDraft(
                    product.getId(), product.getName(), product.getSpec(),
                    product.getUnit(), product.getCoverUrl(),
                    price, pointsPrice, quantity,
                    price * quantity, pointsPrice * quantity);
        }

        OrderItem toEntity(BizOrder order) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            item.setItemType(1);
            item.setItemId(productId);
            // 快照字段：商品后续改价改名都不影响历史订单
            item.setItemName(name);
            item.setItemSpec(spec);
            item.setItemUnit(unit);
            item.setItemCover(cover);
            item.setPrice(price);
            item.setPoints(pointsPrice);
            item.setQuantity(quantity);
            item.setSubtotalAmount(subtotalAmount);
            item.setSubtotalPoints(subtotalPoints);
            return item;
        }
    }
}
