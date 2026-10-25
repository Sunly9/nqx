package com.nqx.web.aspect;

import com.nqx.common.annotation.Idempotent;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.RedisKeyConstants;
import com.nqx.common.exception.BizException;
import com.nqx.common.util.ServletUtils;
import com.nqx.service.satoken.CurrentUserUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 接口幂等切面。
 *
 * <p>基于 Redis {@code SETNX} 实现：方法执行前抢占一个带过期时间的 Key，
 * 抢不到说明同一业务动作正在处理或刚处理过，直接拦截。
 *
 * <h3>为什么用 SETNX 而不是「先 GET 再 SET」？</h3>
 * 后者存在竞态窗口：两个并发请求可能同时 GET 到 null，然后都 SET 成功，
 * 双双通过校验。{@code SETNX}（Redis 的 {@code SET key value NX EX ttl}）
 * 是单条命令、由 Redis 单线程串行执行，天然原子，不存在这个窗口。
 *
 * <h3>三个决定成败的细节</h3>
 * <ol>
 *   <li><b>业务失败必须释放 Key。</b>否则用户输错参数后，在过期时间内
 *       连改都改不了，体验极差且会被当成系统故障。</li>
 *   <li><b>必须有合理的过期时间。</b>Key 若不过期，一旦某次请求异常终止
 *       没走到释放逻辑，该用户将永久无法再调用这个接口，只能等重启。</li>
 *   <li><b>Key 必须包含用户维度。</b>否则 A 提交成功后，B 也会被拦截 ——
 *       这是幂等实现里最隐蔽的错误，在单人测试时完全发现不了。</li>
 * </ol>
 *
 * <h3>关于 SpEL 表达式的安全性</h3>
 * {@link StandardEvaluationContext} 允许表达式调用任意静态方法
 * （例如 {@code T(java.lang.Runtime).getRuntime()}）。
 * 本项目中的表达式全部来自开发者手写的注解，不涉及用户输入，因此是安全的。
 * <b>但绝不能把用户可控的字符串当作 SpEL 表达式解析</b>，那是典型的 RCE 漏洞。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /** 占位值，具体内容不重要，只用来表示"这个 Key 已被占用" */
    private static final String PLACEHOLDER = "1";

    private final StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint point, Idempotent idempotent) throws Throwable {
        String key = buildKey(point, idempotent);

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, PLACEHOLDER, Duration.ofSeconds(idempotent.expire()));

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("拦截重复提交 | key={} | uri={} | 用户={}",
                    key, ServletUtils.getRequestUri(), CurrentUserUtils.getLoginKey());
            throw new BizException(ResultCode.REPEAT_SUBMIT,
                    StringUtils.hasText(idempotent.message())
                            ? idempotent.message()
                            : ResultCode.REPEAT_SUBMIT.getMsg());
        }

        try {
            return point.proceed();
        } catch (Throwable e) {
            // 业务执行失败 → 释放占位，允许用户立即重试。
            // 若这里不释放，"参数写错 → 修正 → 重试"这条最正常的用户路径会被挡 5~10 秒。
            stringRedisTemplate.delete(key);
            throw e;
        }
        // 成功则**不删除** Key，让它在 expire 秒内持续拦截重复请求
    }

    /**
     * 构造幂等 Key：{@code nqx:idempotent:<业务前缀>:<业务键>}
     */
    private String buildKey(ProceedingJoinPoint point, Idempotent idempotent) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();

        // 未指定 prefix 时退化为「类名:方法名」，保证不同接口之间不会互相干扰
        String prefix = StringUtils.hasText(idempotent.prefix())
                ? idempotent.prefix()
                : method.getDeclaringClass().getSimpleName() + ":" + method.getName();

        String bizKey = StringUtils.hasText(idempotent.key())
                ? evaluateSpel(idempotent.key(), method, point.getArgs())
                : CurrentUserUtils.getLoginKey();

        return RedisKeyConstants.IDEMPOTENT_PREFIX + prefix + ":" + bizKey;
    }

    /**
     * 解析 SpEL 表达式，例如 {@code #dto.orderNo}、{@code #userId}。
     *
     * <p>不用 {@code MethodBasedEvaluationContext} 而是手工把参数名绑成变量：
     * 前者对根对象的处理在不同 Spring 版本下行为略有差异，
     * 手工绑定变量后 {@code #参数名} 的引用语义完全确定。
     */
    private String evaluateSpel(String expression, Method method, Object[] args) {
        try {
            EvaluationContext context = new StandardEvaluationContext();
            String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            if (parameterNames != null) {
                for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                    context.setVariable(parameterNames[i], args[i]);
                }
            }
            Object value = EXPRESSION_PARSER.parseExpression(expression).getValue(context);
            return value == null ? "null" : value.toString();
        } catch (Exception e) {
            // 表达式写错属于开发期缺陷，必须让它显式失败而不是静默退化成一个共用 Key
            // （静默退化会导致所有用户共用一个幂等 Key，把正常用户全部拦掉）
            log.error("幂等 Key 表达式解析失败 | 表达式={} | 方法={}", expression, method.getName(), e);
            throw new BizException(ResultCode.SYSTEM_ERROR, "幂等 Key 表达式配置错误：" + expression);
        }
    }
}
