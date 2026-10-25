package com.nqx.service.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.constant.RedisKeyConstants;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.enums.OrderStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订单超时关闭任务。
 *
 * <h2>两个任务，各有分工</h2>
 *
 * <p><b>① 延迟队列扫描（精确）</b><br>
 * 每 10~30 秒从 Redis ZSet 取出到点的订单号并关闭。
 * 精确到秒级，且扫描成本与订单总量无关。
 *
 * <p><b>② 扫库补偿（兜底）</b><br>
 * 每 5 分钟扫一次数据库，把"已过超时时间但仍是待支付"的订单捞出来关闭。
 * 它解决延迟队列自身的不可靠性：
 * <ul>
 *   <li>Redis 未开持久化时重启会丢任务；</li>
 *   <li>ZSet 被误清、key 被误删；</li>
 *   <li>定时任务所在实例在任务执行前挂了。</li>
 * </ul>
 *
 * <p><b>「主链路 + 兜底补偿」是分布式定时任务的标准写法。</b>
 * 只做延迟队列，遇到上述任一情况就会出现永远卡在"待支付"的脏订单；
 * 只做扫库，则失去时效性且随数据量劣化。
 *
 * <h2>多实例并发</h2>
 *
 * <p>延迟队列靠 {@code ZREM} 的原子性抢占（见 {@link OrderDelayQueue}）。
 * 补偿任务却无法这样抢占 —— 它是"查出一批再逐个处理"，
 * 多个实例会查出同一批。因此这里用 Redisson 的分布式锁
 * 保证同一时刻只有一个实例执行。
 *
 * <p>锁的持有时间是 120 秒，比任务最长执行时间略长；
 * 用 {@code tryLock(0, ...)} 而不是阻塞等待，抢不到就立即返回 ——
 * 定时任务该"下次再来"，而不是排队堆积。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderDelayQueue orderDelayQueue;
    private final PurchaseOrderService purchaseOrderService;
    private final BizOrderMapper bizOrderMapper;
    private final RedissonClient redissonClient;

    @Value("${nqx.order.timeout-scan-batch-size:50}")
    private int batchSize;

    @Value("${nqx.order.timeout-minutes:30}")
    private int timeoutMinutes;

    /**
     * 延迟队列扫描：处理到点的订单。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：
     * 前者是"上一次执行结束后再等 N 毫秒"，后者是"每 N 毫秒触发一次"。
     * 若某次扫描因 Redis 抖动耗时较长，fixedRate 会导致任务重叠执行，
     * 两个线程同时处理同一批订单（虽然 ZREM 能保证不重复关闭，但会浪费资源）。
     */
    @Scheduled(fixedDelayString = "${nqx.order.timeout-scan-interval-ms:30000}")
    public void scanDelayQueue() {
        List<String> dueOrders;
        try {
            dueOrders = orderDelayQueue.pollDue(batchSize);
        } catch (Exception e) {
            // Redis 不可用时不能抛出：定时任务里抛异常只会打印堆栈，
            // 下一次调度仍会继续，反而刷屏。记错误并返回即可。
            log.error("延迟队列扫描失败（Redis 异常？本次跳过，等待下次调度）", e);
            return;
        }

        if (dueOrders.isEmpty()) {
            return;
        }

        int closedCount = 0;
        for (String orderNo : dueOrders) {
            try {
                if (purchaseOrderService.closeIfUnpaid(orderNo)) {
                    closedCount++;
                }
            } catch (Exception e) {
                // 单个订单失败绝不能中断整批 —— 否则一个坏数据会卡住后面所有订单的超时关闭
                log.error("关闭超时订单失败，继续处理下一条 | orderNo={}", orderNo, e);
            }
        }
        log.info("延迟队列处理完成 | 到期={} | 实际关闭={}", dueOrders.size(), closedCount);
    }

    /**
     * 扫库补偿：兜住错过延迟队列时间窗的订单。
     *
     * <p>加了分布式锁，保证多实例下只有一个在执行。
     */
    @Scheduled(fixedDelayString = "${nqx.order.compensate-scan-interval-ms:300000}")
    public void compensateMissedOrders() {
        RLock lock = redissonClient.getLock(RedisKeyConstants.LOCK_ORDER_COMPENSATE_TASK);
        boolean locked = false;
        try {
            // waitTime=0：抢不到就立刻返回，不排队
            // leaseTime=120s：即使实例崩溃，锁也会自动释放，不会永久卡住补偿任务
            locked = lock.tryLock(0, 120, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("补偿任务已被其它实例执行，本次跳过");
                return;
            }
            doCompensate();
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉 InterruptedException 会让上层无法感知线程被要求停止，
            // 是并发编程里的经典错误
            Thread.currentThread().interrupt();
            log.warn("补偿任务被中断");
        } catch (Exception e) {
            log.error("补偿任务执行异常", e);
        } finally {
            // isHeldByCurrentThread 判断必不可少：
            // 若 tryLock 失败或锁已因超时释放，直接 unlock 会抛 IllegalMonitorStateException
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doCompensate() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);

        // 用 Page 而不是 last("LIMIT n")：last 是字符串拼接，
        // 虽然这里是常量不会有注入风险，但 Page 更规范也便于将来加排序条件
        Page<BizOrder> page = new Page<>(1, batchSize);
        List<BizOrder> staleOrders = bizOrderMapper.selectPage(page,
                Wrappers.<BizOrder>lambdaQuery()
                        .eq(BizOrder::getStatus, OrderStatusEnum.PENDING_PAY.getCode())
                        .lt(BizOrder::getCreateTime, deadline)
                        .orderByAsc(BizOrder::getCreateTime)).getRecords();

        if (staleOrders.isEmpty()) {
            return;
        }

        log.warn("补偿任务发现 {} 笔超时未关闭的订单（延迟队列可能丢过任务）", staleOrders.size());
        int closedCount = 0;
        for (BizOrder order : staleOrders) {
            try {
                // 复用与延迟队列完全相同的关闭逻辑，保证两条路径行为一致 ——
                // 若各写一套，迟早会出现"延迟队列关掉的单回滚了库存、补偿任务关掉的没回滚"
                if (purchaseOrderService.closeIfUnpaid(order.getOrderNo())) {
                    closedCount++;
                }
            } catch (Exception e) {
                log.error("补偿关闭订单失败 | orderNo={}", order.getOrderNo(), e);
            }
        }
        log.info("补偿任务完成 | 扫描={} | 关闭={}", staleOrders.size(), closedCount);
    }
}
