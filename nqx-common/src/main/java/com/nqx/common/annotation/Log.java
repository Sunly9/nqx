package com.nqx.common.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解。
 *
 * <p>标注在 Controller 方法上，由 {@code LogAspect} 切面自动记录：
 * 操作人、模块、操作描述、请求参数（<b>脱敏后</b>）、响应结果、IP、耗时、异常信息，
 * 并异步落库到 {@code sys_operation_log}。
 *
 * <p><b>使用示例</b>：
 * <pre>{@code
 * @Log(module = "订单管理", operation = "审核回收订单")
 * @PostMapping("/{orderNo}/audit")
 * public Result<Void> audit(@PathVariable String orderNo, @RequestBody AuditDTO dto) { ... }
 * }</pre>
 *
 * <p><b>两个容易忽略的点</b>：
 * <ol>
 *   <li>落库必须<b>异步</b>，否则每次写日志都多一次数据库 IO，拖慢主流程；</li>
 *   <li>入参必须<b>脱敏</b>，password、phone、openid 这类字段要打码，
 *       否则操作日志本身就成了数据泄露源。</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /** 业务模块，如「订单管理」 */
    String module() default "";

    /** 操作描述，如「审核回收订单」 */
    String operation() default "";

    /** 是否记录请求参数 */
    boolean saveParam() default true;

    /** 是否记录响应结果（默认关闭：响应体通常很大，且可能含敏感数据） */
    boolean saveResult() default false;
}
