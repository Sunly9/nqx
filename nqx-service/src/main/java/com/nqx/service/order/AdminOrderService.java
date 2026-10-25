package com.nqx.service.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.AppUserMapper;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.mapper.RecycleCategoryMapper;
import com.nqx.pojo.dto.order.AdminOrderQuery;
import com.nqx.pojo.dto.order.ConfirmRecycleDTO;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.RecycleCategory;
import com.nqx.pojo.enums.OrderActionEnum;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.RecycleSettlementVO;
import com.nqx.pojo.vo.order.AdminOrderVO;
import com.nqx.service.content.ContentService;
import com.nqx.service.points.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台订单服务：审核、驳回、确认回收、结算发积分。
 *
 * <p>与 {@code RecycleOrderService} 放在同一个包（{@code com.nqx.service.order}），
 * 以便复用它的包级方法 {@code findByIdOrOrderNo} / {@code writeFlow} ——
 * 这些方法不该暴露给其它模块调用（否则容易出现绕过归属校验的写法），
 * 用包可见性把访问范围收窄是最轻量的约束手段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    /** 碳减排系数的兜底值（每公斤回收物对应的减排量） */
    private static final BigDecimal DEFAULT_CARBON_FACTOR = new BigDecimal("0.5");

    /** 每公斤积分的兜底值（未选择回收品类时使用） */
    private static final int DEFAULT_POINTS_PER_KG = 100;

    private final BizOrderMapper bizOrderMapper;
    private final AppUserMapper appUserMapper;
    private final RecycleCategoryMapper recycleCategoryMapper;
    private final RecycleOrderService recycleOrderService;
    private final OrderStateMachine orderStateMachine;
    private final PointsService pointsService;
    private final ContentService contentService;

    // ==================== 可用动作 ====================

    /**
     * 当前订单状态下允许执行的动作。
     *
     * <p><b>这个方法解决的是"前后端各维护一份状态规则"的同步问题。</b>
     * 没有它的时候，前端要自己写一份映射表来决定"这个状态下该显示
     * 审核通过还是驳回还是确认回收"；一旦后端调整了状态机
     * （新增状态、改变流转条件），前端的映射表不会自动跟着变，
     * 于是用户会看到一个点了就报"当前状态不允许该操作"的按钮。
     *
     * <p>把状态机的判断结果直接下发，前端只负责渲染 ——
     * <b>规则的唯一来源留在后端</b>，这类不一致就从根本上消失了。
     *
     * <p>返回的是 {@code LinkedHashMap}（key=动作名，value=目标状态的中文说明），
     * 顺序与枚举声明顺序一致，前端按钮排列稳定。
     */
    public Map<String, String> availableActions(String orderNo) {
        BizOrder order = recycleOrderService.findByIdOrOrderNo(orderNo);
        return orderStateMachine.describeAvailableActions(
                OrderTypeEnum.of(order.getOrderType()),
                OrderStatusEnum.of(order.getStatus()));
    }

    // ==================== 列表 ====================

    /** 多条件分页检索（动态 SQL 见 BizOrderMapper.xml） */
    public PageResult<AdminOrderVO> page(AdminOrderQuery query) {
        Page<AdminOrderVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<AdminOrderVO> result = bizOrderMapper.selectAdminPage(page, query);
        // 文案字段无法在 SQL 里优雅地算出（枚举在 Java 侧），统一在这里补齐。
        // 放在后端补而不是让前端维护一份映射表 —— 那份表迟早会和后端的状态定义脱节。
        result.getRecords().forEach(this::fillTextFields);
        return PageUtil.of(result);
    }

    /**
     * 导出用：取符合条件的**全部**订单（上限 {@code maxRows} 条）。
     *
     * <h3>为什么复用同一个 Mapper 查询，而不是另写一条导出 SQL</h3>
     *
     * <p>导出的筛选条件与列表页完全一致（同样的动态 SQL：状态、类型、时间区间、
     * 关键字）。如果另写一条，两处的条件迟早会不一致 —— 出现"列表里筛出 30 条，
     * 导出来 28 条"这种对不上的结果，而两边都"看起来正常"，极难排查。
     *
     * <h3>{@code new Page<>(1, maxRows, false)} 里的 false</h3>
     *
     * <p>第三个参数是 {@code searchCount}。导出**不需要总数**，
     * 关掉它能省掉一条 {@code SELECT COUNT(*)} —— 订单表数据量大时这条 COUNT
     * 可能比取数据本身还慢。
     *
     * <p>上限 {@code maxRows} 是刻意的：导出无上限会把整个结果集读进内存，
     * 数据量一大就 OOM。宁可截断并告知，也不要让一次导出拖垮服务。
     */
    public List<AdminOrderVO> exportList(AdminOrderQuery query, int maxRows) {
        Page<AdminOrderVO> page = new Page<>(1, maxRows, false);
        IPage<AdminOrderVO> result = bizOrderMapper.selectAdminPage(page, query);
        result.getRecords().forEach(this::fillTextFields);
        return result.getRecords();
    }

    // ==================== 审核 ====================

    /** 审核通过：待审核 → 待回收 */
    @Transactional(rollbackFor = Exception.class)
    public void audit(String orderNo, String remark, Long adminId) {
        BizOrder order = requireRecycleOrder(orderNo);
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.RECYCLE, current, OrderActionEnum.AUDIT_PASS);

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        update.setAuditorId(adminId);
        update.setAuditTime(LocalDateTime.now());
        update.setAuditRemark(remark);
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        recycleOrderService.writeFlow(order, current, target.getCode(),
                OrderActionEnum.AUDIT_PASS, adminId, CommonConstants.OPERATOR_TYPE_ADMIN, remark);
        log.info("回收单审核通过 | orderNo={} | adminId={}", orderNo, adminId);
    }

    /** 驳回：待审核 → 已驳回。驳回原因必填，否则用户不知道该改什么 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(String orderNo, String reason, Long adminId) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(ResultCode.PARAM_ERROR, "请填写驳回原因");
        }
        BizOrder order = requireRecycleOrder(orderNo);
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());
        OrderStatusEnum target = orderStateMachine.transfer(
                OrderTypeEnum.RECYCLE, current, OrderActionEnum.REJECT);

        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(target.getCode());
        update.setAuditorId(adminId);
        update.setAuditTime(LocalDateTime.now());
        update.setRejectReason(reason);
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        recycleOrderService.writeFlow(order, current, target.getCode(),
                OrderActionEnum.REJECT, adminId, CommonConstants.OPERATOR_TYPE_ADMIN, reason);
        log.info("回收单已驳回 | orderNo={} | 原因={}", orderNo, reason);
    }

    // ==================== 确认回收 + 结算 ====================

    /**
     * 确认回收，可选同时结算发积分。
     *
     * <p>业务动作拆成两步是为了保留一个真实的业务检查点：
     * 「货已收到」与「已按实际重量结算」在管理上是两件事
     * （可能还要复检、称重记录需要复核）。若现场称重后直接结算，
     * 传 {@code settle=true}（默认），两个状态会在同一事务内连续流转，
     * 流转日志里会留下两条记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecycleSettlementVO confirmRecycle(String orderNo, ConfirmRecycleDTO dto, Long adminId) {
        BizOrder order = requireRecycleOrder(orderNo);
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());

        // 状态机校验：只有「待回收」能确认回收
        OrderStatusEnum collected = orderStateMachine.transfer(
                OrderTypeEnum.RECYCLE, current, OrderActionEnum.CONFIRM_RECYCLE);

        BigDecimal actualWeight = dto.getActualWeight();
        int pointsPerKg = resolvePointsPerKg(order.getRecycleCategoryId());
        // 积分向下取整：宁可少给一点，也不能因为四舍五入多发给用户
        // （多发出去的积分是要不回来的，而少发的可以走管理员调整补上）
        int points = actualWeight.multiply(BigDecimal.valueOf(pointsPerKg))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        BigDecimal carbon = actualWeight.multiply(resolveCarbonFactor())
                .setScale(2, RoundingMode.HALF_UP);

        // ---------- 第一步：记录实际重量，推进到「已回收」 ----------
        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(collected.getCode());
        update.setActualWeight(actualWeight);
        update.setCarbonSaved(carbon);
        update.setAuditorId(adminId);
        update.setAuditTime(LocalDateTime.now());
        update.setAuditRemark(dto.getRemark());
        update.setVersion(nextVersion(order));
        bizOrderMapper.updateById(update);

        recycleOrderService.writeFlow(order, current, collected.getCode(),
                OrderActionEnum.CONFIRM_RECYCLE, adminId, CommonConstants.OPERATOR_TYPE_ADMIN,
                String.format("现场称重 %.2f kg", actualWeight));

        boolean doSettle = dto.getSettle() == null || dto.getSettle();
        if (!doSettle) {
            log.info("回收单已确认收货（未结算）| orderNo={} | 实际重量={}kg", orderNo, actualWeight);
            return RecycleSettlementVO.of(order.getOrderNo(), actualWeight,
                    null, carbon, null, collected.getDesc());
        }

        return settleInternal(order, collected, actualWeight, points, carbon, adminId, dto.getRemark());
    }

    /**
     * 单独结算：已回收 → 已完成，并发放积分。
     *
     * <p>用于「先确认收货、后结算」的两步流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecycleSettlementVO settle(String orderNo, Long adminId, String remark) {
        BizOrder order = requireRecycleOrder(orderNo);
        OrderStatusEnum current = OrderStatusEnum.of(order.getStatus());

        // 若已确认收货，用实际重量；否则拒绝（不允许绕过称重直接结算）
        if (current != OrderStatusEnum.COLLECTED || order.getActualWeight() == null) {
            throw new BizException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "请先确认回收并录入实际重量后再结算");
        }

        BigDecimal actualWeight = order.getActualWeight();
        int pointsPerKg = resolvePointsPerKg(order.getRecycleCategoryId());
        int points = actualWeight.multiply(BigDecimal.valueOf(pointsPerKg))
                .setScale(0, RoundingMode.DOWN).intValue();
        BigDecimal carbon = actualWeight.multiply(resolveCarbonFactor())
                .setScale(2, RoundingMode.HALF_UP);

        return settleInternal(order, current, actualWeight, points, carbon, adminId, remark);
    }

    /**
     * 结算核心逻辑。
     *
     * <p><b>重复结算的安全性由两层保证</b>：
     * <ol>
     *   <li>状态机：已完成的状态没有 SETTLE 流转，第二次调用直接被拒；</li>
     *   <li>积分唯一索引：即便状态机判断被并发绕过，
     *       {@code points_record} 上 {@code (user_id, orderNo, RECYCLE_REWARD)}
     *       的唯一索引也会拦住第二次发放，并让整个事务回滚。</li>
     * </ol>
     * 只有一层是不够的 —— 这是"防御性设计"该有的样子。
     */
    private RecycleSettlementVO settleInternal(BizOrder order, OrderStatusEnum from,
                                               BigDecimal actualWeight, int points, BigDecimal carbon,
                                               Long adminId, String remark) {
        OrderStatusEnum finished = orderStateMachine.transfer(
                OrderTypeEnum.RECYCLE, from, OrderActionEnum.SETTLE);

        String categoryName = resolveCategoryName(order.getRecycleCategoryId());

        // ---------- 发放积分（幂等键 = 订单号） ----------
        Integer balanceAfter = null;
        if (points > 0) {
            balanceAfter = pointsService.reward(
                    order.getUserId(), points, PointsChangeTypeEnum.RECYCLE_REWARD,
                    order.getOrderNo(), order.getId(),
                    String.format("回收%s %.2fkg 获得积分", categoryName, actualWeight));
        } else {
            log.warn("回收单结算积分为 0，跳过发放 | orderNo={} | 实际重量={}kg | 每公斤积分={}",
                    order.getOrderNo(), actualWeight, resolvePointsPerKg(order.getRecycleCategoryId()));
        }

        // ---------- 累加用户回收统计（原子递增，避免并发丢更新） ----------
        appUserMapper.addRecycleStats(order.getUserId(), actualWeight, carbon);

        // ---------- 推进到终态并记录积分 ----------
        BizOrder update = new BizOrder();
        update.setId(order.getId());
        update.setStatus(finished.getCode());
        update.setTotalPoints(points);
        update.setFinishTime(LocalDateTime.now());
        update.setVersion(nextVersion(order) + 1);
        bizOrderMapper.updateById(update);

        recycleOrderService.writeFlow(order, from, finished.getCode(),
                OrderActionEnum.SETTLE, adminId, CommonConstants.OPERATOR_TYPE_ADMIN,
                String.format("结算完成，发放 %d 积分，碳减排 %.2f kg", points, carbon));

        log.info("回收单结算完成 | orderNo={} | 用户={} | 实际重量={}kg | 发放积分={} | 碳减排={}kg",
                order.getOrderNo(), order.getUserId(), actualWeight, points, carbon);

        return RecycleSettlementVO.of(order.getOrderNo(), actualWeight,
                points, carbon, balanceAfter, finished.getDesc());
    }

    // ==================== 私有方法 ====================

    /** 取订单并校验确实是回收单 —— 避免采购单被误传进回收流程 */
    private BizOrder requireRecycleOrder(String orderNo) {
        BizOrder order = recycleOrderService.findByIdOrOrderNo(orderNo);
        if (!OrderTypeEnum.RECYCLE.getCode().equals(order.getOrderType())) {
            throw new BizException(ResultCode.ORDER_TYPE_INVALID, "该订单不是回收单");
        }
        return order;
    }

    /**
     * 解析本单的积分单价。
     *
     * <p>优先用回收品类自己的单价（氧化银含银，每公斤价值高于碱性电池），
     * 未选品类时退回站点配置的兜底单价。
     */
    private int resolvePointsPerKg(Long categoryId) {
        if (categoryId != null) {
            RecycleCategory category = recycleCategoryMapper.selectById(categoryId);
            if (category != null && category.getPointsPerKg() != null) {
                return category.getPointsPerKg();
            }
        }
        return contentService.getIntSetting("recycle_points_per_kg", DEFAULT_POINTS_PER_KG);
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) {
            return "纽扣电池";
        }
        RecycleCategory category = recycleCategoryMapper.selectById(categoryId);
        return category == null ? "纽扣电池" : category.getName();
    }

    private BigDecimal resolveCarbonFactor() {
        String value = contentService.getSetting("carbon_factor", null);
        if (!StringUtils.hasText(value)) {
            return DEFAULT_CARBON_FACTOR;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("碳减排系数配置非法，使用默认值 | value={}", value);
            return DEFAULT_CARBON_FACTOR;
        }
    }

    private Integer nextVersion(BizOrder order) {
        return order.getVersion() == null ? 1 : order.getVersion() + 1;
    }

    private void fillTextFields(AdminOrderVO vo) {
        OrderTypeEnum type = OrderTypeEnum.of(vo.getOrderType());
        vo.setOrderTypeText(type == null ? "未知" : type.getDesc());
        OrderStatusEnum status = OrderStatusEnum.of(vo.getStatus());
        vo.setStatusText(status == null ? "未知" : status.getDesc());
    }
}
