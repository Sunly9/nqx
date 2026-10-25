package com.nqx.web.filter;

import com.nqx.common.constant.CommonConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器 —— 为每个请求生成唯一的 traceId，写入 MDC 与响应头。
 *
 * <p><b>它解决什么问题？</b>
 * 线上一个接口出错时，日志里同时混杂着成百上千个并发请求的输出。
 * 有了 traceId，就能用 {@code grep <traceId>} 把这一次请求涉及的所有日志
 * （过滤器、拦截器、Service、MyBatis、异常处理）完整串起来。
 *
 * <p><b>三个设计要点</b>：
 * <ol>
 *   <li><b>优先复用上游传入的 traceId</b>。若网关或前端已经生成了 traceId，
 *       通过 {@code X-Trace-Id} 请求头传入，则直接沿用 —— 这样才能做到跨服务全链路一致。
 *       只有上游没传时才自己生成。</li>
 *   <li><b>继承 {@link OncePerRequestFilter} 而非直接实现 {@code Filter}</b>。
 *       Servlet 规范下同一个请求可能因 forward/include/异步派发而多次经过过滤器，
 *       直接实现会导致 traceId 被反复覆盖；{@code OncePerRequestFilter}
 *       保证每个请求只执行一次。</li>
 *   <li><b>必须在 finally 中清理 MDC</b>。Tomcat 的工作线程是复用的，
 *       不清理会导致 traceId 泄漏到后续完全无关的请求日志上，反而造成误导。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** traceId 长度：16 位十六进制，碰撞概率可忽略且不占日志空间 */
    private static final int TRACE_ID_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = request.getHeader(CommonConstants.TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = generateTraceId();
        }

        MDC.put(CommonConstants.TRACE_ID, traceId);
        // 回写到响应头，前端在报错时可以把 traceId 一并反馈给后端，直接定位到那一次请求
        response.setHeader(CommonConstants.TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CommonConstants.TRACE_ID);
        }
    }

    private String generateTraceId() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, TRACE_ID_LENGTH);
    }
}
