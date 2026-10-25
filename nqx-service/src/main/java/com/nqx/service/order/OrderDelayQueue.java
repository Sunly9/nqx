package com.nqx.service.order;

import com.nqx.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 订单超时延迟队列（基于 Redis ZSet）。
 *
 * <h2>为什么不用定时任务扫全表？</h2>
 *
 * <p>最朴素的做法是每 30 秒执行一次
 * {@code SELECT * FROM biz_order WHERE status = 40 AND create_time < NOW() - INTERVAL 30 MINUTE}，
 * 然后逐条关闭。它能用，但有两个硬伤：
 * <ul>
 *   <li><b>时间不精确</b>：订单在"30 分钟 ~ 30 分钟 + 扫描间隔"之间被关闭，
 *       用户看到的是"我还没到时间它就关了"或"过了 5 分钟才关"；</li>
 *   <li><b>扫描成本随数据量线性增长</b>：表里有几百万历史订单后，
 *       每次扫描都要遍历一遍索引区间，即便绝大多数是早已关闭的订单。</li>
 * </ul>
 *
 * <p>ZSet 方案把"什么时候该处理哪个订单"直接存进 Redis：
 * score 是应当关闭的时间戳，member 是订单号。
 * 扫描时只需 {@code ZRANGEBYSCORE key 0 now} 取到点的那些，精确且与总量无关。
 *
 * <h2>多实例并发怎么办？</h2>
 *
 * <p>生产环境通常部署多个实例，定时任务会在每个实例上同时触发，
 * 于是同一个订单号会被多个实例同时取到、重复处理。
 *
 * <p>解法是用 {@code ZREM} 的返回值做<b>分布式抢占</b>：
 * ZSet 的 {@code ZREM} 是原子操作，同一个 member 只有一个调用者能删成功（返回 1）。
 * 用它当作"抢到这条消息"的标志，比额外引入一把分布式锁更轻量。
 *
 * <p>这个技巧值得记住：<b>Redis 里很多"返回值即锁"的操作
 * （ZREM、SETNX、INCR、LPOP）都能用来做无锁的抢占。</b>
 *
 * <h2>与消息队列的关系（面试常问）</h2>
 *
 * <p>延迟消息的三种主流实现：定时扫表 / Redis ZSet / MQ 延迟队列（RabbitMQ 死信、
 * RocketMQ 延迟级别）。本项目选 ZSet 的原因是：量级不大、无需引入额外中间件、
 * 且能顺带把"原子抢占"讲清楚。缺点是 Redis 若未开持久化，
 * 重启会丢任务 —— 因此<b>必须配一个扫库补偿任务兜底</b>（见 {@link OrderTimeoutTask}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayQueue {

    /**
     * 原子出队脚本。
     *
     * <p>{@code ZREM} 返回被删除的成员数：返回 1 表示本实例抢到了这条任务，
     * 返回 0 表示已被其它实例抢走（或已被移除）。
     *
     * <p>用 Lua 而不是"先 ZRANGEBYSCORE 再 ZREM"两步：两步之间存在竞态，
     * 两个实例可能都查到同一条、都去处理。虽然 ZREM 的返回值仍能区分胜负，
     * 但把判断与删除合并到一次 Redis 往返里更清晰、也少一次网络开销。
     */
    private static final DefaultRedisScript<Long> POP_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "if removed == 1 then return 1 else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 投递一条延迟任务。
     *
     * @param orderNo      订单号（作为 ZSet 的 member）
     * @param delaySeconds 延迟秒数
     */
    public void push(String orderNo, long delaySeconds) {
        long executeAt = System.currentTimeMillis() + delaySeconds * 1000L;
        stringRedisTemplate.opsForZSet().add(RedisKeyConstants.DELAY_ORDER_QUEUE, orderNo, executeAt);
        log.debug("投递延迟关闭任务 | orderNo={} | 延迟={}秒", orderNo, delaySeconds);
    }

    /**
     * 移除延迟任务。
     *
     * <p><b>本项目刻意没有在"支付成功"时调用它。</b>
     * 原因：支付事务若在提交后回滚（或后续步骤抛异常），
     * 延迟任务已经删掉，这单就永远不会被超时关闭，最终卡在"待支付"成为脏数据。
     * 让任务留在队列里、由处理方先查一次订单状态再决定是否关闭，
     * 用一次多余的查询换取"绝不漏关"的确定性 —— 这笔账很划算。
     *
     * <p>方法保留是给"订单被主动取消、确实不该再等到点"这类确定性场景用的。
     */
    public void remove(String orderNo) {
        stringRedisTemplate.opsForZSet().remove(RedisKeyConstants.DELAY_ORDER_QUEUE, orderNo);
    }

    /**
     * 拉取并抢占到期的任务。
     *
     * @param maxCount 单次最多处理多少条，避免一次拉取过多把线程占满
     * @return 本实例成功抢到的订单号列表
     */
    public List<String> pollDue(int maxCount) {
        long now = System.currentTimeMillis();
        Set<String> candidates = stringRedisTemplate.opsForZSet()
                .rangeByScore(RedisKeyConstants.DELAY_ORDER_QUEUE, 0, now, 0, maxCount);

        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> acquired = new ArrayList<>(candidates.size());
        for (String orderNo : candidates) {
            Long removed = stringRedisTemplate.execute(
                    POP_SCRIPT, Collections.singletonList(RedisKeyConstants.DELAY_ORDER_QUEUE), orderNo);
            if (removed != null && removed == 1L) {
                acquired.add(orderNo);
            }
        }
        log.debug("延迟队列扫描 | 候选={} | 抢到={}", candidates.size(), acquired.size());
        return acquired;
    }

    /** 当前队列积压量，供监控与排查使用 */
    public long size() {
        Long size = stringRedisTemplate.opsForZSet().zCard(RedisKeyConstants.DELAY_ORDER_QUEUE);
        return size == null ? 0L : size;
    }
}
