package com.nqx.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nqx.common.constant.RedisKeyConstants;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis 缓存服务 —— 基于「逻辑过期 + 互斥重建」实现。
 *
 * <h2>为什么不用最简单的「设置 TTL、过期就查库」？</h2>
 *
 * <p>那是 Cache-Aside 的基本形态，但存在**缓存击穿**问题：
 * 一个热点 Key（比如首页最热门的商品）在过期的那一瞬间，
 * 所有并发请求都会发现缓存没了，然后同时去查数据库。
 * 假设 QPS 是 5000，这个热点 Key 每秒过期一次，数据库就要承受一次 5000 并发的冲击 ——
 * 足以把连接池打满，进而拖垮整个系统。
 *
 * <h2>逻辑过期是怎么解决的？</h2>
 *
 * <p>关键在于：**物理上不设置（或设置很长的）TTL，过期时间写在缓存值内部**。
 * 于是 Key 永远不会真正从 Redis 消失，读取时：
 * <ol>
 *   <li>值存在且未逻辑过期 → 直接返回；</li>
 *   <li>值存在但已逻辑过期 → <b>仍然把旧值返回给当前请求</b>，
 *       同时尝试获取互斥锁，拿到锁的那个线程去异步重建缓存；</li>
 *   <li>值不存在（首次访问 / Redis 被清空）→ 只能同步查库并回填。</li>
 * </ol>
 *
 * <p><b>核心收益：任何时刻都不会出现"大量请求同时打数据库"</b>。
 * 逻辑过期的瞬间，所有请求都立刻拿到了（稍旧的）数据，
 * 只有拿到锁的那一个线程去查库，而且是在后台线程里做，不阻塞用户请求。
 *
 * <h2>代价（必须能说出来）</h2>
 * <ol>
 *   <li><b>牺牲强一致性换取可用性</b>：逻辑过期期间返回的是旧数据。
 *       对商品详情这种"改价后晚几秒生效也能接受"的场景完全没问题；
 *       但对库存、余额这类数据<b>绝不能</b>用这套缓存。</li>
 *   <li><b>需要额外存储 expireAt</b>，且缓存值必须是包装结构，不能直接存业务对象。</li>
 *   <li><b>异步重建依赖线程池</b>，线程池若被打满，重建会延迟，旧数据停留时间变长。</li>
 * </ol>
 *
 * <h2>与其它两种方案的关系（面试常问）</h2>
 * <table border="1">
 *   <tr><th>方案</th><th>一致性</th><th>可用性</th><th>适用</th></tr>
 *   <tr><td>TTL + 互斥锁重建</td><td>较高</td><td>重建期间请求被阻塞</td><td>数据量小、重建快</td></tr>
 *   <tr><td><b>逻辑过期（本类）</b></td><td>最终一致</td><td>不阻塞，始终有数据</td><td><b>热点数据、重建耗时</b></td></tr>
 *   <tr><td>定时任务预热</td><td>取决于周期</td><td>无重建延迟</td><td>数据量有限、可枚举</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    /** 互斥锁的持有时间，够重建一次缓存即可；过长会在重建失败后长时间无法重试 */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(10);

    /** 物理 TTL 在逻辑过期时间之上额外叠加的时长，保证 Key 在逻辑过期后仍存在一段时间 */
    private static final long PHYSICAL_TTL_FACTOR = 3L;

    /**
     * 空值哨兵。
     *
     * <p>用于<b>缓存穿透</b>防护：当查询的 ID 在数据库中确实不存在时，
     * 若什么都不缓存，每次请求都会穿透到数据库。攻击者只要用一批不存在的 ID
     * 持续请求（例如商品 ID 用 -1、99999999），就能绕过缓存直接压垮数据库。
     * 缓存一个"明确表示不存在"的哨兵值，即可把这类请求挡在缓存层。
     */
    private static final String NULL_SENTINEL = "__NQX_NULL__";

    /**
     * 空值缓存的存活时长。
     *
     * <p>刻意设置得很短（1 分钟），因为"不存在"这个结论本身可能很快失效
     * （数据被创建出来了）。用长 TTL 会导致"新增了数据却查不到"的诡异现象。
     */
    private static final long NULL_CACHE_SECONDS = 60;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor nqxTaskExecutor;

    /** 逻辑过期时间（秒），默认 30 分钟 */
    @Value("${nqx.cache.product-expire-seconds:1800}")
    private long logicalExpireSeconds;

    /** 叠加的随机秒数，用于打散过期时间、防止缓存雪崩 */
    @Value("${nqx.cache.random-expire-seconds:300}")
    private long randomExpireSeconds;

    /**
     * 带逻辑过期的读取。
     *
     * @param key    缓存 Key（建议用 {@link RedisKeyConstants} 中定义的前缀拼装）
     * @param type   业务对象类型，用于反序列化
     * @param loader 缓存未命中或需要重建时，如何从数据库加载
     * @param <T>    业务对象类型
     */
    public <T> T getWithLogicalExpire(String key, Class<T> type, Supplier<T> loader) {
        String cached = stringRedisTemplate.opsForValue().get(key);

        // ---------- 情况一：缓存不存在 ----------
        if (cached == null) {
            log.debug("缓存未命中，同步加载 | key={}", key);
            return loadAndCache(key, type, loader);
        }

        LogicalCache wrapper = parseWrapper(key, cached);
        // ---------- 情况二：缓存值损坏 ----------
        if (wrapper == null) {
            log.warn("缓存值格式异常，按未命中处理 | key={}", key);
            return loadAndCache(key, type, loader);
        }

        long now = System.currentTimeMillis();
        // 判断这条记录是"真实数据"还是"空值哨兵"
        boolean isNullEntry = NULL_SENTINEL.equals(wrapper.getJson());

        // ---------- 情况三：未逻辑过期 ----------
        if (now < wrapper.getExpireAt()) {
            return isNullEntry ? null : deserialize(key, wrapper.getJson(), type);
        }

        // ---------- 情况四：已逻辑过期 ----------
        // 先返回旧数据，保证请求不被阻塞、数据库不被打爆
        T staleValue = isNullEntry ? null : deserialize(key, wrapper.getJson(), type);
        if (tryAcquireRebuildLock(key)) {
            log.info("缓存逻辑过期，触发异步重建 | key={}", key);
            asyncRebuild(key, loader);
        } else {
            log.debug("缓存逻辑过期，但重建锁已被其它实例持有，返回旧数据 | key={}", key);
        }
        return staleValue;
    }

    /**
     * 删除缓存。
     *
     * <p><b>调用时机</b>：数据变更时（新增/修改/删除商品）。
     * 本类采用「先更新数据库，再删除缓存」的 Cache-Aside 策略，
     * 而不是"更新缓存" —— 因为更新缓存会引入并发写覆盖问题：
     * 两个线程分别更新数据库和缓存，顺序交错时缓存里会留下旧值。
     * 删除则让下一次读取自然地从库里加载最新值，问题小得多。
     *
     * <p><b>这套策略的残留缺陷（要能主动说出来）</b>：
     * 若"删除缓存"这一步失败（网络抖动、Redis 短暂不可用），
     * 缓存里就会长期残留旧数据，直到下一次有人更新或逻辑过期。
     * 工业界的补救手段是「延迟双删」（更新后延迟几百毫秒再删一次）
     * 或「订阅 binlog 异步删除」（Canal）。本项目为控制复杂度未引入，
     * 依靠逻辑过期时间兜底 —— 最长 30 分钟后一定会刷新。
     */
    public void evict(String key) {
        stringRedisTemplate.delete(key);
        log.debug("已删除缓存 | key={}", key);
    }

    // ==================== 私有实现 ====================

    /**
     * 同步加载并回填缓存，同时释放可能持有的重建锁。
     *
     * <p>「缓存不存在」与「值损坏」两条路径走这里。
     * 它不加锁保护：这两种情况要么是首次访问、要么是异常数据，
     * 不会像热点 Key 过期那样形成高频并发。
     */
    private <T> T loadAndCache(String key, Class<T> type, Supplier<T> loader) {
        try {
            T value = loader.get();
            writeResult(key, value);
            return value;
        } finally {
            releaseRebuildLock(key);
        }
    }

    /** 异步重建：不在用户请求线程里查库，避免拖慢响应 */
    private <T> void asyncRebuild(String key, Supplier<T> loader) {
        nqxTaskExecutor.execute(() -> {
            try {
                T fresh = loader.get();
                writeResult(key, fresh);
                log.info("缓存异步重建完成 | key={} | 数据是否存在={}", key, fresh != null);
            } catch (Exception e) {
                // 重建失败不能影响任何用户请求，只需留下线索。
                // 旧数据仍在缓存中，下个请求会再次尝试重建。
                log.error("缓存异步重建失败 | key={}", key, e);
            } finally {
                releaseRebuildLock(key);
            }
        });
    }

    /** 统一写出：有数据写正常缓存，无数据写空值哨兵（防穿透） */
    private <T> void writeResult(String key, T value) {
        if (value == null) {
            writeNullCache(key);
            log.debug("数据不存在，写入空值缓存以防穿透 | key={}", key);
        } else {
            writeCache(key, value);
        }
    }

    /** 写入空值哨兵，TTL 很短，避免"数据已创建却仍查不到" */
    private void writeNullCache(String key) {
        LogicalCache wrapper = new LogicalCache();
        wrapper.setExpireAt(System.currentTimeMillis() + NULL_CACHE_SECONDS * 1000L);
        wrapper.setJson(NULL_SENTINEL);
        String json = toJson(wrapper);
        if (json != null) {
            stringRedisTemplate.opsForValue()
                    .set(key, json, Duration.ofSeconds(NULL_CACHE_SECONDS));
        }
    }

    private <T> void writeCache(String key, T value) {
        LogicalCache wrapper = new LogicalCache();
        long ttlSeconds = logicalExpireSeconds + randomOffset();
        wrapper.setExpireAt(System.currentTimeMillis() + ttlSeconds * 1000L);
        wrapper.setJson(toJson(value));
        if (wrapper.getJson() == null) {
            return;
        }
        // 物理 TTL 远大于逻辑过期时间：保证 Key 不会在逻辑过期前被 Redis 淘汰，
        // 否则就退化成了普通的 TTL 缓存，逻辑过期也就失去意义
        String wrapperJson = toJson(wrapper);
        if (wrapperJson != null) {
            stringRedisTemplate.opsForValue()
                    .set(key, wrapperJson, Duration.ofSeconds(ttlSeconds * PHYSICAL_TTL_FACTOR));
        }
    }

    /** 序列化封装，失败时返回 null 而不是抛异常 —— 缓存是旁路功能，不能拖垮主流程 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("缓存序列化失败 | type={}", value == null ? "null" : value.getClass().getSimpleName(), e);
            return null;
        }
    }

    /**
     * 随机偏移量，用于打散过期时间。
     *
     * <p>若所有 Key 都在同一时刻过期（比如批量预热后同时写入），
     * 就会出现**缓存雪崩**：大量 Key 同时失效，请求全部涌向数据库。
     * 给每个 Key 的过期时间叠加一个随机量，就能把失效时刻摊开。
     */
    private long randomOffset() {
        if (randomExpireSeconds <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(randomExpireSeconds + 1);
    }

    private LogicalCache parseWrapper(String key, String cached) {
        try {
            return objectMapper.readValue(cached, LogicalCache.class);
        } catch (Exception e) {
            log.warn("缓存包装结构解析失败 | key={}", key);
            return null;
        }
    }

    private <T> T deserialize(String key, String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // 类型不匹配通常意味着 VO 结构改过但缓存没清。
            // 返回 null 会让调用方按"未命中"继续处理，同时留下告警。
            log.error("缓存反序列化失败（可能 VO 结构已变更，建议排查后清缓存）| key={} | type={}",
                    key, type.getSimpleName(), e);
            return null;
        }
    }

    private String rebuildLockKey(String key) {
        return RedisKeyConstants.LOCK_PREFIX + "cache:" + key;
    }

    /** 抢占重建锁：SETNX 是原子操作，多实例部署下只有一个节点能抢到 */
    private boolean tryAcquireRebuildLock(String key) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(rebuildLockKey(key), "1", REBUILD_LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseRebuildLock(String key) {
        stringRedisTemplate.delete(rebuildLockKey(key));
    }

    /**
     * 缓存包装结构：在业务数据之外额外携带逻辑过期时间。
     *
     * <p>用「时间戳 + 业务 JSON 字符串」而不是泛型对象，
     * 是为了让本类保持通用：不需要知道业务对象的类型就能完成读写与过期判断。
     */
    @Data
    public static class LogicalCache {

        /** 逻辑过期时间戳（毫秒）。超过它即视为过期，但数据本身仍会被返回 */
        private long expireAt;

        /** 业务对象的 JSON 序列化结果 */
        private String json;
    }
}
