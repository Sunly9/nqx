package com.nqx.common.constant;

/**
 * Redis Key 统一管理。
 *
 * <p><b>为什么一定要集中定义前缀？</b>
 * <ol>
 *   <li><b>避免键冲突</b>：不同业务用不同前缀，否则"商品缓存"和"用户缓存"可能撞键；</li>
 *   <li><b>可运维</b>：线上排查时能用 {@code SCAN nqx:cache:product:*} 精确批量查看/清理，
 *       而散落各处的字符串拼接根本无法枚举；</li>
 *   <li><b>可隔离</b>：统一以 {@code nqx:} 开头，多个项目共用一个 Redis 实例时不会互相污染。</li>
 * </ol>
 *
 * <p><b>命名规范</b>：{@code nqx:<用途>:<业务>:<标识>}
 */
public interface RedisKeyConstants {

    /** 全局前缀：所有键都以此开头，便于运维批量管理 */
    String PREFIX = "nqx:";

    // ==================== 缓存 ====================

    /** 商品详情缓存：nqx:cache:product:{productId} */
    String CACHE_PRODUCT = PREFIX + "cache:product:";

    /** 商品分类列表缓存 */
    String CACHE_CATEGORY_LIST = PREFIX + "cache:category:list";

    /** 回收品类列表缓存 */
    String CACHE_RECYCLE_CATEGORY_LIST = PREFIX + "cache:recycle:category:list";

    /** 商品列表缓存（按查询条件哈希作为后缀） */
    String CACHE_PRODUCT_LIST = PREFIX + "cache:product:list:";

    /** 缓存互斥锁：nqx:cache:lock:product:{productId}，用于击穿保护 */
    String CACHE_LOCK_PRODUCT = PREFIX + "cache:lock:product:";

    /**
     * 逻辑过期时间字段名。
     * 缓存值序列化为 JSON 后，额外包裹 {@code expireAt} 字段；
     * 读取时若 {@code now > expireAt} 则判定为逻辑过期 —— 这是「逻辑过期」方案的核心。
     */
    String FIELD_EXPIRE_AT = "expireAt";

    // ==================== 分布式锁 ====================

    String LOCK_PREFIX = PREFIX + "lock:";

    /** 订单超时扫描定时任务的多实例互斥锁 */
    String LOCK_ORDER_TIMEOUT_TASK = LOCK_PREFIX + "order:timeout-task";

    /** 订单超时补偿任务（扫库兜底） */
    String LOCK_ORDER_COMPENSATE_TASK = LOCK_PREFIX + "order:compensate-task";

    /** 积分结算锁：nqx:lock:points:settle:{orderNo} */
    String LOCK_POINTS_SETTLE = LOCK_PREFIX + "points:settle:";

    // ==================== 接口幂等 ====================

    /** nqx:idempotent:{prefix}:{bizKey} */
    String IDEMPOTENT_PREFIX = PREFIX + "idempotent:";

    // ==================== 延迟队列 ====================

    /**
     * 订单超时延迟队列（ZSet）。
     * score = 订单应关闭的时间戳，member = orderNo。
     */
    String DELAY_ORDER_QUEUE = PREFIX + "delay:order";

    // ==================== 排行榜 ====================

    /** 积分排行榜（ZSet），score = 累计获得积分 */
    String RANK_POINTS = PREFIX + "rank:points";

    // ==================== 限流 ====================

    /** 接口限流：nqx:rate-limit:{接口标识}:{用户标识} */
    String RATE_LIMIT_PREFIX = PREFIX + "rate-limit:";

    // ==================== 安全 ====================

    /** 登录失败次数计数：nqx:login:fail:{username} */
    String LOGIN_FAIL_PREFIX = PREFIX + "login:fail:";
}
