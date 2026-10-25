package com.nqx.service.order;

import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.pojo.enums.OrderActionEnum;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单状态机。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>没有状态机时，订单流转的合法性校验会散落在各个业务方法里：
 *
 * <pre>{@code
 * // 审核方法里
 * if (order.getStatus() != 10) throw new BizException("状态不对");
 * // 确认回收方法里
 * if (order.getStatus() != 11) throw new BizException("状态不对");
 * // 取消方法里
 * if (order.getStatus() != 10 && order.getStatus() != 11 && order.getStatus() != 40) ...
 * }</pre>
 *
 * <p>这种写法的三个问题：
 * <ol>
 *   <li><b>规则分散</b>：想知道"订单一共有哪些流转路径"，得把所有方法读一遍；</li>
 *   <li><b>魔法数字</b>：{@code status != 10} 完全看不出是什么意思；</li>
 *   <li><b>容易漏</b>：新增一个状态时，忘记在某个方法里加判断，就会产生非法流转
 *       （例如"已完成的订单又被取消"，导致积分重复发放）。</li>
 * </ol>
 *
 * <p>把流转规则集中到一张表里之后，所有状态变更都必须先过 {@link #transfer}，
 * 非法流转在<b>入口</b>就被拦住，且规则只有一处定义。
 *
 * <h2>为什么用「订单类型 → 当前状态 → 动作 → 目标状态」三级映射</h2>
 *
 * <p>回收单与采购单是两套独立的状态机（见 {@link OrderStatusEnum} 的说明），
 * 用订单类型作为第一级键，天然把两套规则隔离开，
 * 业务代码里也就不需要出现 {@code if (orderType == 1) {...} else {...}} 这种分支。
 *
 * <h2>线程安全</h2>
 * <p>规则表在构造时一次性建好，之后只读，因此是线程安全的
 * （顶层用 {@link Collections#unmodifiableMap} 显式表达不可变意图）。
 */
@Slf4j
@Component
public class OrderStateMachine {

    /**
     * 流转规则表。
     * 结构：订单类型 → 当前状态 → 动作 → 目标状态
     */
    private final Map<OrderTypeEnum, Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>>> rules;

    public OrderStateMachine() {
        this.rules = buildRules();
        // 启动即自检：规则表写错（比如把采购单的动作配到了回收单状态上）
        // 应该在应用启动时就炸出来，而不是等某个用户操作时才抛异常
        validate(this.rules);
        log.info("订单状态机初始化完成 | 回收单 {} 条流转规则 | 采购单 {} 条流转规则",
                ruleCount(OrderTypeEnum.RECYCLE), ruleCount(OrderTypeEnum.PURCHASE));
    }

    /**
     * 执行状态流转，非法流转直接抛业务异常。
     *
     * <p>业务代码里所有状态变更都应通过本方法计算目标状态，
     * 不要自己写 {@code order.setStatus(...)}。
     */
    public OrderStatusEnum transfer(OrderTypeEnum type, OrderStatusEnum current, OrderActionEnum action) {
        OrderStatusEnum target = findTarget(type, current, action);
        if (target == null) {
            throw new BizException(ResultCode.ORDER_STATUS_ILLEGAL, String.format(
                    "%s当前状态为「%s」，不允许执行「%s」操作",
                    type == null ? "订单" : type.getDesc(),
                    current == null ? "未知" : current.getDesc(),
                    action.getDesc()));
        }
        return target;
    }

    /** 查询流转目标，不合法时返回 null（供「是否显示某按钮」这类判断使用） */
    public OrderStatusEnum findTarget(OrderTypeEnum type, OrderStatusEnum current, OrderActionEnum action) {
        if (type == null || current == null || action == null) {
            return null;
        }
        return rules.getOrDefault(type, Collections.emptyMap())
                .getOrDefault(current, Collections.emptyMap())
                .get(action);
    }

    public boolean canTransfer(OrderTypeEnum type, OrderStatusEnum current, OrderActionEnum action) {
        return findTarget(type, current, action) != null;
    }

    /**
     * 列出当前状态下所有可执行的动作及目标状态。
     *
     * <p>小程序端 / 后台可用它来决定"这条订单该显示哪些操作按钮"，
     * 从而把状态机规则**下发到前端**，避免前端自己硬编码一套判断
     * （前端硬编码的规则迟早会和后端不一致，出现"按钮点了报错"的体验问题）。
     */
    public Map<OrderActionEnum, OrderStatusEnum> availableActions(OrderTypeEnum type, OrderStatusEnum current) {
        Map<OrderActionEnum, OrderStatusEnum> actions =
                rules.getOrDefault(type, Collections.emptyMap()).get(current);
        return actions == null ? Collections.emptyMap() : Collections.unmodifiableMap(actions);
    }

    /**
     * 订单创建后的初始状态。
     *
     * @param type         订单类型
     * @param paidByPoints 是否以积分支付（积分兑换场景）
     *                     <p>积分在下单时即时扣减，因此无需再"支付"，
     *                     直接进入待发货；若走模拟结账则需要先支付。
     */
    public OrderStatusEnum initialStatus(OrderTypeEnum type, boolean paidByPoints) {
        if (type == OrderTypeEnum.RECYCLE) {
            return OrderStatusEnum.PENDING_AUDIT;
        }
        return paidByPoints ? OrderStatusEnum.PAID : OrderStatusEnum.PENDING_PAY;
    }

    // ==================== 规则定义 ====================

    private static Map<OrderTypeEnum, Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>>> buildRules() {
        Map<OrderTypeEnum, Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>>> all =
                new EnumMap<>(OrderTypeEnum.class);

        // ---------------- 回收单 ----------------
        Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>> recycle =
                new EnumMap<>(OrderStatusEnum.class);
        rule(recycle, OrderStatusEnum.PENDING_AUDIT, OrderActionEnum.AUDIT_PASS, OrderStatusEnum.AUDIT_PASSED);
        rule(recycle, OrderStatusEnum.PENDING_AUDIT, OrderActionEnum.REJECT, OrderStatusEnum.REJECTED);
        rule(recycle, OrderStatusEnum.PENDING_AUDIT, OrderActionEnum.CANCEL, OrderStatusEnum.CANCELLED);
        rule(recycle, OrderStatusEnum.AUDIT_PASSED, OrderActionEnum.CONFIRM_RECYCLE, OrderStatusEnum.COLLECTED);
        // 审核通过后、回收前，用户仍可取消（比如临时不想交了）
        rule(recycle, OrderStatusEnum.AUDIT_PASSED, OrderActionEnum.CANCEL, OrderStatusEnum.CANCELLED);
        rule(recycle, OrderStatusEnum.COLLECTED, OrderActionEnum.SETTLE, OrderStatusEnum.RECYCLE_FINISHED);
        all.put(OrderTypeEnum.RECYCLE, recycle);

        // ---------------- 采购单 ----------------
        Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>> purchase =
                new EnumMap<>(OrderStatusEnum.class);
        rule(purchase, OrderStatusEnum.PENDING_PAY, OrderActionEnum.PAY, OrderStatusEnum.PAID);
        rule(purchase, OrderStatusEnum.PENDING_PAY, OrderActionEnum.CANCEL, OrderStatusEnum.CANCELLED);
        rule(purchase, OrderStatusEnum.PENDING_PAY, OrderActionEnum.TIMEOUT_CLOSE, OrderStatusEnum.CLOSED);
        rule(purchase, OrderStatusEnum.PAID, OrderActionEnum.DELIVER, OrderStatusEnum.DELIVERED);
        // 注意：已支付待发货的状态**不允许**用户取消 —— 钱已扣、货已在备，
        // 需要走退款流程，那是另一套业务（本项目未实现）。状态机天然挡住了这条非法路径。
        rule(purchase, OrderStatusEnum.DELIVERED, OrderActionEnum.CONFIRM_RECEIPT, OrderStatusEnum.PURCHASE_FINISHED);
        all.put(OrderTypeEnum.PURCHASE, purchase);

        return Collections.unmodifiableMap(all);
    }

    private static void rule(Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>> machine,
                             OrderStatusEnum from, OrderActionEnum action, OrderStatusEnum to) {
        machine.computeIfAbsent(from, k -> new EnumMap<>(OrderActionEnum.class)).put(action, to);
    }

    /**
     * 启动期自检。
     *
     * <p>检查规则表里出现的每个状态是否真的属于它所在的那台状态机。
     * 例如把 {@code PAID（采购单状态）} 配到回收单的流转表里，
     * 就属于配置错误 —— 这种错误不会导致编译失败，
     * 但会让"回收单跳到待发货"这种荒谬的流转成为合法操作。
     */
    private static void validate(Map<OrderTypeEnum, Map<OrderStatusEnum, Map<OrderActionEnum, OrderStatusEnum>>> all) {
        all.forEach((type, machine) -> machine.forEach((from, actions) -> {
            if (!from.belongsTo(type)) {
                throw new IllegalStateException(String.format(
                        "订单状态机配置错误：%s 的流转表中出现了不属于它的起始状态 %s",
                        type.getDesc(), from.name()));
            }
            actions.forEach((action, to) -> {
                if (!to.belongsTo(type)) {
                    throw new IllegalStateException(String.format(
                            "订单状态机配置错误：%s 的流转表将 %s --%s--> %s，而 %s 不属于 %s",
                            type.getDesc(), from.name(), action.name(), to.name(), to.name(), type.getDesc()));
                }
            });
        }));
        // 两类订单都必须有规则，漏配会导致所有操作都被判为非法流转
        for (OrderTypeEnum type : OrderTypeEnum.values()) {
            if (!all.containsKey(type) || all.get(type).isEmpty()) {
                throw new IllegalStateException("订单状态机配置错误：" + type.getDesc() + "缺少流转规则");
            }
        }
    }

    private long ruleCount(OrderTypeEnum type) {
        return rules.getOrDefault(type, Collections.emptyMap())
                .values().stream()
                .mapToLong(Map::size)
                .sum();
    }

    /**
     * 供接口返回「当前状态下可用动作」时使用。
     *
     * <p>用 {@link LinkedHashMap} 保证输出顺序与枚举声明顺序一致，
     * 前端按钮的排列才是稳定的。
     */
    public Map<String, String> describeAvailableActions(OrderTypeEnum type, OrderStatusEnum current) {
        Map<String, String> result = new LinkedHashMap<>();
        availableActions(type, current).forEach((action, status) ->
                result.put(action.name(), status.getDesc()));
        return result;
    }
}
