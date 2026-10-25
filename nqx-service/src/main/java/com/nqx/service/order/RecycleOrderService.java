package com.nqx.service.order;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.mapper.OrderFlowMapper;
import com.nqx.mapper.RecycleCategoryMapper;
import com.nqx.pojo.dto.app.RecycleOrderCreateDTO;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.OrderFlow;
import com.nqx.pojo.entity.RecycleCategory;
import com.nqx.pojo.enums.OrderActionEnum;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.AppOrderVO;
import com.nqx.service.satoken.StpAppUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 回收单服务。
 *
 * <p>回收业务与采购业务共用 {@code biz_order} 主表（用 {@code order_type} 区分），
 * 但状态流转是两套独立的规则，由 {@link OrderStateMachine} 按订单类型分发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleOrderService {

    /** 回收方式：自行寄送 */
    private static final String METHOD_SEND = "send";

    /** 回收方式：上门回收 */
    private static final String METHOD_PICKUP = "pickup";

    /** 订单号冲突时的最大重试次数 */
    private static final int MAX_ORDER_NO_RETRY = 3;

    /** 兼容前端可能传来的多种时间格式，按"越精确越靠前"的顺序尝试 */
    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    );

    private final BizOrderMapper bizOrderMapper;
    private final OrderFlowMapper orderFlowMapper;
    private final RecycleCategoryMapper recycleCategoryMapper;
    private final OrderStateMachine orderStateMachine;
    private final OrderNoGenerator orderNoGenerator;

    /**
     * 提交回收单。
     *
     * <p><b>整个方法在一个事务里</b>，因为要同时写三处数据：
     * 订单主表、订单明细、流转日志。任何一步失败都必须整体回滚，
     * 否则会出现"订单存在但没有流转记录"这种查不到进度、客服也说不清的状态。
     *
     * <p>注意：本方法<b>不发放积分</b>。积分要等管理员现场称重确认后，
     * 按<b>实际重量</b>结算 —— 用户自报的重量只能用于调度参考。
     */
    @Transactional(rollbackFor = Exception.class)
    public AppOrderVO create(RecycleOrderCreateDTO dto) {
        Long userId = StpAppUtil.getLoginIdAsLong();

        String recycleMethod = normalizeMethod(dto.getType());
        RecycleCategory category = loadCategoryIfPresent(dto.getRecycleCategoryId());

        BizOrder order = new BizOrder();
        order.setUserId(userId);
        order.setOrderType(OrderTypeEnum.RECYCLE.getCode());
        order.setRecycleCategoryId(dto.getRecycleCategoryId());
        order.setRecycleMethod(recycleMethod);
        // 初始状态由状态机决定，不写死数字 —— 状态机是流转规则的唯一来源
        order.setStatus(orderStateMachine.initialStatus(OrderTypeEnum.RECYCLE, false).getCode());

        order.setEstimatedWeight(dto.getWeight());
        order.setContactName(dto.getContactName());
        order.setContactPhone(dto.getContactPhone());
        order.setAddressDetail(dto.getAddress());
        order.setCourierNo(dto.getCourierNo());
        order.setExpectTime(parsePickupTime(dto.getPickupTime()));
        order.setUserRemark(dto.getRemark());
        order.setAttachmentUrls(toJsonArray(dto.getImages()));

        // 金额与积分在提交阶段都是 0：回收是"用户给平台东西"，不存在应收金额；
        // 积分要等实际重量确认后才产生
        order.setTotalQuantity(0);
        order.setTotalAmount(0);
        order.setTotalPoints(0);
        order.setVersion(0);
        order.setDeleted(CommonConstants.DELETED_NO);

        insertWithUniqueOrderNo(order);

        writeFlow(order, null, order.getStatus(), OrderActionEnum.SUBMIT,
                userId, CommonConstants.OPERATOR_TYPE_USER,
                "用户提交回收单（" + (METHOD_PICKUP.equals(recycleMethod) ? "上门回收" : "自行寄送") + "）");

        log.info("回收单已提交 | orderNo={} | userId={} | 品类={} | 预估重量={}kg | 方式={}",
                order.getOrderNo(), userId,
                category == null ? "未选择" : category.getName(),
                dto.getWeight(), recycleMethod);

        return AppOrderVO.from(order);
    }

    /**
     * 我的回收单列表。
     *
     * <p>用户身份来自 token，不接受请求参数传入 ——
     * 旧版接口用 {@code ?user_id=} 查询参数识别用户，改个数字就能看别人的订单。
     */
    public PageResult<AppOrderVO> page(Integer statusCode, long pageNum, long pageSize) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        Page<BizOrder> page = new Page<>(
                pageNum < 1 ? CommonConstants.PAGE_NUM_DEFAULT : pageNum,
                pageSize < 1 ? CommonConstants.PAGE_SIZE_DEFAULT
                        : Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX));

        Page<BizOrder> result = bizOrderMapper.selectPage(page, Wrappers.<BizOrder>lambdaQuery()
                .eq(BizOrder::getUserId, userId)
                .eq(BizOrder::getOrderType, OrderTypeEnum.RECYCLE.getCode())
                .eq(statusCode != null, BizOrder::getStatus, statusCode)
                // 与 biz_order 的 idx_user_type_status_time 索引顺序一致：
                // user_id → order_type → status → create_time，排序走索引不产生 filesort
                .orderByDesc(BizOrder::getCreateTime)
                .orderByDesc(BizOrder::getId));

        return PageUtil.of(result, AppOrderVO::from);
    }

    /**
     * 回收单详情。
     *
     * @param idOrOrderNo 支持主键 ID 与订单号两种形式
     *                    <p>旧版前端传的是数字主键（{@code /api/orders/12}），
     *                    新版更希望用订单号（对外暴露主键会泄露业务量，
     *                    也便于用户报单号给客服）。这里两种都接受：
     *                    纯数字按主键查，否则按订单号查。
     *                    <b>用主键查时必须同时校验归属</b>，否则改个数字就能看别人的单。
     */
    public AppOrderVO detail(String idOrOrderNo) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = findOwnOrder(idOrOrderNo, userId);
        return AppOrderVO.from(order);
    }

    /** 订单流转进度（时间轴） */
    public List<OrderFlow> flowList(String idOrOrderNo) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = findOwnOrder(idOrOrderNo, userId);
        return orderFlowMapper.selectList(Wrappers.<OrderFlow>lambdaQuery()
                .eq(OrderFlow::getOrderId, order.getId())
                .orderByAsc(OrderFlow::getCreateTime)
                .orderByAsc(OrderFlow::getId));
    }

    /**
     * 用户取消回收单。
     *
     * <p>是否允许取消<b>由状态机判断</b>，而不是在业务代码里写 if：
     * 已回收待结算、已完成、已驳回的单都不允许取消，
     * 这些规则集中在 {@link OrderStateMachine} 的流转表里。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String idOrOrderNo, String reason) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        BizOrder order = findOwnOrder(idOrOrderNo, userId);

        OrderTypeEnum type = OrderTypeEnum.of(order.getOrderType());
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        // 非法流转会在这里直接抛业务异常，附带可读的提示
        OrderStatusEnum target = orderStateMachine.transfer(type, current, OrderActionEnum.CANCEL);

        updateStatus(order, current, target, userId,
                CommonConstants.OPERATOR_TYPE_USER, reason);

        log.info("用户取消回收单 | orderNo={} | userId={} | {} -> {}",
                order.getOrderNo(), userId, current.name(), target.name());
    }

    // ==================== 包级可见：供管理端服务复用 ====================

    /**
     * 按 id 或订单号查找订单，并校验归属当前用户。
     *
     * <p>抽成包级方法供其它服务复用，保证"归属校验"不会被漏掉 ——
     * 这是防止水平越权的关键一步，绝不能在每个业务方法里各写一遍。
     */
    BizOrder findOwnOrder(String idOrOrderNo, Long userId) {
        BizOrder order = findByIdOrOrderNo(idOrOrderNo);
        if (!order.getUserId().equals(userId)) {
            // 不用"订单不存在"敷衍，而是明确告知无权操作：
            // 前者会让用户以为数据丢了，反复来问；后者语义准确
            throw new BizException(ResultCode.ORDER_NOT_BELONG_TO_USER);
        }
        return order;
    }

    /** 按主键或订单号查找（不校验归属，供管理端使用） */
    BizOrder findByIdOrOrderNo(String idOrOrderNo) {
        if (!StringUtils.hasText(idOrOrderNo)) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        BizOrder order;
        if (idOrOrderNo.chars().allMatch(Character::isDigit)) {
            order = bizOrderMapper.selectById(Long.valueOf(idOrOrderNo));
        } else {
            order = bizOrderMapper.selectOne(Wrappers.<BizOrder>lambdaQuery()
                    .eq(BizOrder::getOrderNo, idOrOrderNo));
        }
        BizException.assertNotNull(order, ResultCode.ORDER_NOT_FOUND);
        return order;
    }

    /** 更新状态并写流转日志。所有状态变更都必须走这里，保证日志不遗漏 */
    void updateStatus(BizOrder order, OrderStatusEnum from, OrderStatusEnum to,
                      Long operatorId, int operatorType, String remark) {
        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(to.getCode());
        // 说明：version 是一个「变更次数计数器」，不是真正的乐观锁。
        // 真正的乐观锁需要实体字段加 @Version 注解，让 MyBatis-Plus 自动生成
        // `WHERE version = ?` 条件。本项目没有启用它，原因是：
        //   1) 状态机已经挡住了绝大多数并发非法流转（第二次请求会因为状态不合法被拒）；
        //   2) 真正的重复结算由 points_record 的唯一索引兜底，这是更可靠的防线；
        //   3) 启用 @Version 后，所有 updateById 都必须传旧版本号，
        //      漏传会导致更新静默失败（WHERE version = null 匹配不到行），
        //      这是个很容易埋雷的约束，收益不抵风险。
        // 保留这个计数器是为了排查时能看出"这条单被改过几次"，将来若要启用真乐观锁也有现成的列。
        update.setVersion(order.getVersion() == null ? 1 : order.getVersion() + 1);
        if (to == OrderStatusEnum.CANCELLED) {
            update.setCancelTime(LocalDateTime.now());
            update.setCancelReason(remark);
        } else if (to == OrderStatusEnum.RECYCLE_FINISHED
                || to == OrderStatusEnum.PURCHASE_FINISHED) {
            update.setFinishTime(LocalDateTime.now());
        }
        bizOrderMapper.updateById(update);

        writeFlow(order, from, to.getCode(), resolveAction(from, to), operatorId, operatorType, remark);
    }

    /** 写一条流转日志 */
    void writeFlow(BizOrder order, OrderStatusEnum from, Integer toStatus, OrderActionEnum action,
                   Long operatorId, int operatorType, String remark) {
        OrderFlow flow = new OrderFlow();
        flow.setOrderId(order.getId());
        flow.setOrderNo(order.getOrderNo());
        // fromStatus 用 0 表示"无前置状态"，即订单创建时的第一条日志。
        // order_flow 的状态列是 NOT NULL，不能用 null 表达。
        flow.setFromStatus(from == null ? 0 : from.getCode());
        flow.setToStatus(toStatus);
        flow.setAction(action.name());
        flow.setOperatorId(operatorId);
        flow.setOperatorType(operatorType);
        flow.setRemark(remark);
        orderFlowMapper.insert(flow);
    }

    // ==================== 私有方法 ====================

    /** 归一化回收方式，拒绝非法取值 */
    private String normalizeMethod(String type) {
        if (METHOD_PICKUP.equalsIgnoreCase(type)) {
            return METHOD_PICKUP;
        }
        if (METHOD_SEND.equalsIgnoreCase(type)) {
            return METHOD_SEND;
        }
        throw new BizException(ResultCode.PARAM_ERROR, "回收方式只能是 send（自行寄送）或 pickup（上门回收）");
    }

    private RecycleCategory loadCategoryIfPresent(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        RecycleCategory category = recycleCategoryMapper.selectById(categoryId);
        BizException.assertNotNull(category, ResultCode.RECYCLE_CATEGORY_NOT_FOUND);
        if (category.getStatus() != null && category.getStatus() != CommonConstants.STATUS_ENABLED) {
            throw new BizException(ResultCode.RECYCLE_CATEGORY_NOT_FOUND, "该回收品类已停用");
        }
        return category;
    }

    /**
     * 生成订单号并插入，冲突时重试。
     *
     * <p>为什么不"先查是否存在再插入"？那有竞态窗口，两个并发请求会同时查到"不存在"。
     * 正确做法是**直接插入并捕获唯一键冲突**，让数据库来保证唯一性。
     * 重试次数有限，避免极端情况下无限循环。
     */
    private void insertWithUniqueOrderNo(BizOrder order) {
        for (int attempt = 1; attempt <= MAX_ORDER_NO_RETRY; attempt++) {
            order.setOrderNo(orderNoGenerator.generate(OrderTypeEnum.RECYCLE));
            try {
                bizOrderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                // 说明订单号撞了。MySQL 下唯一键冲突不会让事务失效，可以安全重试。
                log.warn("订单号冲突，重试第 {} 次 | orderNo={}", attempt, order.getOrderNo());
            }
        }
        throw new BizException(ResultCode.SYSTEM_ERROR, "订单号生成失败，请重试");
    }

    /**
     * 宽松解析预约时间。
     *
     * <p>解析失败时返回 null 而不是抛异常：预约时间只是辅助信息，
     * 为了它让整张单提交失败是本末倒置。用户能在订单里补填。
     */
    private LocalDateTime parsePickupTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        // 前端可能传 ISO 串（含 T），统一成空格分隔后再按固定格式解析
        String normalized = text.trim().replace('T', ' ');
        // 去掉毫秒与时区后缀
        int dotIndex = normalized.indexOf('.');
        if (dotIndex > 0) {
            normalized = normalized.substring(0, dotIndex);
        }
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }

        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一种格式
            }
        }
        // 只有日期没有时间 → 默认当天 09:00，符合"预约上门"的语义
        try {
            return LocalDate.parse(normalized).atTime(9, 0);
        } catch (DateTimeParseException ignored) {
            log.warn("无法解析预约上门时间，已置空 | 原值={}", text);
            return null;
        }
    }

    private String toJsonArray(List<String> images) {
        if (images == null || images.isEmpty()) {
            // 与 ProductService 同理：写 "[]" 而不是 null，
            // 否则 MyBatis-Plus 的 NOT_NULL 更新策略会让"清空图片"永远无法生效
            return "[]";
        }
        return JSONUtil.toJsonStr(images);
    }

    /** 根据状态变化推断动作，用于补充自动流转的日志（如超时关闭） */
    private OrderActionEnum resolveAction(OrderStatusEnum from, OrderStatusEnum to) {
        if (to == OrderStatusEnum.CANCELLED) {
            return OrderActionEnum.CANCEL;
        }
        if (to == OrderStatusEnum.CLOSED) {
            return OrderActionEnum.TIMEOUT_CLOSE;
        }
        if (from == OrderStatusEnum.PENDING_AUDIT && to == OrderStatusEnum.AUDIT_PASSED) {
            return OrderActionEnum.AUDIT_PASS;
        }
        if (to == OrderStatusEnum.REJECTED) {
            return OrderActionEnum.REJECT;
        }
        if (to == OrderStatusEnum.COLLECTED) {
            return OrderActionEnum.CONFIRM_RECYCLE;
        }
        if (to == OrderStatusEnum.RECYCLE_FINISHED) {
            return OrderActionEnum.SETTLE;
        }
        if (to == OrderStatusEnum.PAID) {
            return OrderActionEnum.PAY;
        }
        if (to == OrderStatusEnum.DELIVERED) {
            return OrderActionEnum.DELIVER;
        }
        if (to == OrderStatusEnum.PURCHASE_FINISHED) {
            return OrderActionEnum.CONFIRM_RECEIPT;
        }
        return OrderActionEnum.SUBMIT;
    }
}
