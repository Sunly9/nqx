package com.nqx.common.annotation;

import java.lang.annotation.*;

/**
 * 接口幂等注解。
 *
 * <p>标注在 Controller 方法上，由 {@code IdempotentAspect} 基于 Redis {@code SETNX} 实现：
 * 方法执行前尝试占位，占位失败说明是重复请求，直接拦截。
 *
 * <p><b>使用示例</b>：
 * <pre>{@code
 * // 同一用户 10 秒内只能提交一次采购单
 * @Idempotent(prefix = "order:submit", key = "#userId", expire = 10)
 * @PostMapping("/purchase")
 * public Result<String> submitPurchase(@RequestBody @Valid OrderCreateDTO dto) { ... }
 * }</pre>
 *
 * <p><b>三个必须处理好的细节（否则会误伤正常用户）</b>：
 * <ol>
 *   <li><b>方法执行失败必须释放 key</b>，否则用户改正参数后 10 秒内无法重试，体验极差；</li>
 *   <li><b>必须有合理的过期时间</b>，不能永久占位，否则重启前该用户再也提交不了；</li>
 *   <li><b>key 要包含用户维度</b>，否则 A 提交后 B 也会被拦。</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 业务前缀，用于区分不同接口，如 {@code order:submit} */
    String prefix() default "";

    /**
     * 业务键的 SpEL 表达式。为空时退化为「方法签名 + 当前登录用户」。
     * <p>示例：{@code "#userId"}、{@code "#dto.orderNo"}、{@code "#dto.id + ':' + #dto.type"}
     */
    String key() default "";

    /** 占位有效期（秒）。应略大于该接口的正常处理耗时 */
    int expire() default 5;

    /** 拦截时的提示信息 */
    String message() default "";
}
