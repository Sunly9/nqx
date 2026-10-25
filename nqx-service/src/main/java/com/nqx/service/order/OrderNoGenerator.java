package com.nqx.service.order;

import com.nqx.pojo.enums.OrderTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单号生成器。
 *
 * <h2>为什么不用自增主键当订单号</h2>
 *
 * <p>自增 ID 会暴露业务量（用户从"我的第 5 单"能推断出平台总订单量只有几千），
 * 也便于遍历他人订单。订单号对外可见，必须与主键解耦。
 *
 * <h2>为什么不用 UUID</h2>
 *
 * <p>UUID 无序，作为 InnoDB 主键会导致页分裂；作为业务单号又太长（36 字符），
 * 用户电话报单号时报不清楚。订单号要能被人工念出来。
 *
 * <h2>本方案：业务前缀 + 时间戳 + 随机数</h2>
 *
 * <pre>
 *   RC 20261015230612 483920
 *   │  └─ 精确到秒的时间  └─ 6 位随机数
 *   └─ 业务前缀（RC=回收单 PC=采购单）
 * </pre>
 *
 * <p>长度 22 字符，落在 {@code VARCHAR(32)} 内。前缀让单号自解释 ——
 * 客服看到 {@code RC} 开头就知道是回收单，不用再去查库。
 *
 * <p><b>唯一性怎么保证？</b> 时间戳到秒 + 6 位随机数，
 * 同一秒内产生 100 万个单号才有约 50% 碰撞概率（生日问题），
 * 对本项目的量级来说碰撞概率可以忽略。
 * 但"可以忽略"不等于"不会发生"，因此 {@code biz_order} 上有
 * {@code uk_order_no} 唯一索引兜底，插入冲突时由调用方重试。
 * <b>不要依赖概率保证正确性，要靠数据库约束。</b>
 */
@Slf4j
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 随机数上界（6 位） */
    private static final int RANDOM_BOUND = 1_000_000;

    public String generate(OrderTypeEnum type) {
        return type.getOrderNoPrefix()
                + LocalDateTime.now().format(TIMESTAMP_FORMAT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(RANDOM_BOUND));
    }
}
