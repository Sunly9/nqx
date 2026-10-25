package com.nqx.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nqx.common.annotation.Log;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.util.SensitiveUtils;
import com.nqx.common.util.ServletUtils;
import com.nqx.pojo.entity.SysOperationLog;
import com.nqx.service.satoken.CurrentUserUtils;
import com.nqx.service.system.OperationLogRecorder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志切面。
 *
 * <p>标注了 {@link Log} 的接口，会自动记录：操作人、模块、操作描述、
 * 脱敏后的入参、响应结果、IP、耗时、异常信息，并异步落库到 {@code sys_operation_log}。
 *
 * <h3>三个必须做对的地方</h3>
 *
 * <p><b>1. 必须原样抛出异常</b><br>
 * 切面里 catch 到异常后只能记录、不能吞掉。若 {@code return null} 代替抛出，
 * 会造成三个连锁后果：全局异常处理器收不到、{@code @Transactional} 不会回滚、
 * 前端拿到 200 却以为成功。这是 AOP 记录日志时最容易犯的错。
 *
 * <p><b>2. 必须在 finally 中落库</b><br>
 * 无论成功还是失败都要留下记录 —— 失败的尝试恰恰是审计关注的重点
 * （谁在反复尝试越权操作？）。
 *
 * <p><b>3. 入参必须过滤与脱敏</b><br>
 * {@code HttpServletRequest}、{@code MultipartFile}、{@code BindingResult}
 * 这些参数无法也不能序列化；密码、手机号等敏感字段必须打码。详见 {@link SensitiveUtils}。
 *
 * <h3>关于切面顺序</h3>
 * 本切面设为 {@code HIGHEST_PRECEDENCE + 10}，比 {@code IdempotentAspect}（+20）更外层。
 * 这意味着幂等拦截抛出的异常也会流经本切面被记录下来 ——
 * 「谁在重复提交」本身就是值得审计的行为。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class LogAspect {

    /** 入参与响应的最大落库长度，超出截断（TEXT 列上限 65535 字节） */
    private static final int MAX_TEXT_LENGTH = 4000;

    /** 异常信息最大长度，对应 error_msg 列的 VARCHAR(1000) */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OperationLogRecorder operationLogRecorder;
    private final ObjectMapper objectMapper;

    @Around("@annotation(logAnnotation)")
    public Object around(ProceedingJoinPoint point, Log logAnnotation) throws Throwable {
        long startTime = System.currentTimeMillis();

        SysOperationLog entity = buildBaseEntity(point, logAnnotation);
        // 执行前先固化一次身份。
        // 原因：注销接口执行完毕后会话已被销毁，这时再去取只能得到 anon，
        // 日志就丢失了"是谁注销的"这一最关键的信息。
        Identity identityBefore = Identity.capture();

        try {
            Object result = point.proceed();
            entity.setStatus(1);
            if (logAnnotation.saveResult() && result != null) {
                entity.setResponseResult(SensitiveUtils.truncate(
                        SensitiveUtils.toMaskedJson(objectMapper, result), MAX_TEXT_LENGTH));
            }
            return result;
        } catch (Throwable e) {
            entity.setStatus(0);
            entity.setErrorMsg(SensitiveUtils.truncate(e.getMessage(), MAX_ERROR_LENGTH));
            // 必须原样抛出：吞掉异常会让全局异常处理失效、事务不回滚
            throw e;
        } finally {
            // 执行后再取一次身份。
            // 原因：登录接口在执行**前**尚未建立会话，只有执行后才拿得到登录人。
            //
            // 两个时机各解决一半问题，因此取"执行后拿得到就用执行后，拿不到就回退到执行前"：
            //   登录接口 → 执行前 anon、执行后 admin → 用 admin  ✅
            //   注销接口 → 执行前 admin、执行后 anon → 用 admin  ✅
            //   普通接口 → 前后一致                      → 用执行后 ✅
            //   登录失败 → 前后都是 anon                 → anon      ✅（符合事实）
            Identity identityAfter = Identity.capture();
            Identity actor = identityAfter.isAnonymous() ? identityBefore : identityAfter;
            actor.applyTo(entity);

            entity.setCostTime(System.currentTimeMillis() - startTime);
            // 异步落库。注意此处调用的是另一个 Bean，@Async 代理才会生效
            operationLogRecorder.record(entity);
        }
    }

    /**
     * 操作人身份快照。
     *
     * <p>用 record 而非散落的三个变量，是为了让「执行前 / 执行后」两次采集
     * 能作为一个整体比较与回退，避免出现"用户类型用了执行前的、用户名用了执行后的"
     * 这种自相矛盾的日志。
     */
    private record Identity(int userType, Long userId, String userName) {

        static Identity capture() {
            return new Identity(
                    CurrentUserUtils.getOperatorType(),
                    CurrentUserUtils.getLoginIdOrNull(),
                    CurrentUserUtils.getLoginName());
        }

        boolean isAnonymous() {
            return CurrentUserUtils.ANONYMOUS.equals(userName);
        }

        void applyTo(SysOperationLog entity) {
            entity.setUserType(userType);
            entity.setUserId(userId);
            entity.setUserName(userName);
        }
    }

    /** 采集请求上下文信息。刻意在主线程完成，避免异步线程取不到请求上下文 */
    private SysOperationLog buildBaseEntity(ProceedingJoinPoint point, Log logAnnotation) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();

        SysOperationLog entity = new SysOperationLog();
        // traceId 与日志文件中的一致，便于从数据库记录反查到完整调用链
        entity.setTraceId(MDC.get(CommonConstants.TRACE_ID));
        entity.setModule(logAnnotation.module());
        entity.setOperation(logAnnotation.operation());
        entity.setMethod(method.getDeclaringClass().getName() + "#" + method.getName());
        entity.setRequestUrl(ServletUtils.getRequestUri());

        HttpServletRequest request = ServletUtils.getRequest();
        entity.setRequestMethod(request == null ? null : request.getMethod());
        entity.setIp(ServletUtils.getClientIp());

        if (logAnnotation.saveParam()) {
            Object filteredArgs = filterArgs(point, signature);
            entity.setRequestParam(SensitiveUtils.truncate(
                    SensitiveUtils.toMaskedJson(objectMapper, filteredArgs), MAX_TEXT_LENGTH));
        }
        return entity;
    }

    /**
     * 过滤掉不可序列化或不该记录的参数。
     *
     * <p>返回 {@code Map<参数名, 值>} 而不是原始数组：日志里出现
     * {@code {"dto": {...}, "orderNo": "RC20261015..."}} 远比
     * {@code [{...}, "RC20261015..."]} 可读，排查问题时不用数下标。
     *
     * <p>参数名依赖编译时保留的 {@code -parameters}（已在父 POM 的
     * maven-compiler-plugin 中开启），否则只能退化成 {@code arg0/arg1}。
     */
    private Object filterArgs(ProceedingJoinPoint point, MethodSignature signature) {
        Object[] args = point.getArgs();
        String[] parameterNames = signature.getParameterNames();
        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            String name = (parameterNames != null && i < parameterNames.length)
                    ? parameterNames[i] : ("arg" + i);

            if (arg == null) {
                continue;
            }
            // Servlet 容器对象：序列化会触发 getInputStream 等副作用，直接跳过
            if (arg instanceof jakarta.servlet.ServletRequest
                    || arg instanceof jakarta.servlet.ServletResponse) {
                continue;
            }
            // 校验结果对象：内容冗长且无审计价值
            if (arg instanceof BindingResult) {
                continue;
            }
            // 流对象：不可序列化
            if (arg instanceof InputStream || arg instanceof OutputStream) {
                continue;
            }
            // 上传文件：只记录文件名与大小，绝不记录文件内容
            if (arg instanceof MultipartFile file) {
                result.put(name, "文件[" + file.getOriginalFilename() + ", " + file.getSize() + " 字节]");
                continue;
            }
            if (arg instanceof MultipartFile[] files) {
                result.put(name, "文件 x" + files.length);
                continue;
            }
            result.put(name, arg);
        }
        return result;
    }
}
