package com.nqx.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Servlet 相关工具。
 */
public final class ServletUtils {

    private ServletUtils() {
    }

    /** 兼容各代理服务器的真实 IP 请求头，按常见优先级排列 */
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private static final String UNKNOWN = "unknown";

    /** 获取当前请求对象，非请求线程（如定时任务）中返回 null */
    public static HttpServletRequest getRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 获取客户端真实 IP。
     *
     * <p><b>为什么不能直接用 {@code request.getRemoteAddr()}？</b>
     * 生产环境前面通常有 Nginx 或网关，{@code getRemoteAddr()} 拿到的会是代理的 IP
     * （经常是 127.0.0.1），导致登录日志、操作日志里的 IP 全部一样，失去审计价值。
     * 真实 IP 需要通过 {@code X-Forwarded-For} 等头部传递。
     *
     * <p><b>两个必须处理的细节</b>：
     * <ol>
     *   <li>{@code X-Forwarded-For} 是一个逗号分隔的链路
     *       （{@code 客户端IP, 代理1, 代理2}），<b>第一个</b>才是客户端真实 IP；</li>
     *   <li>该头部可被客户端伪造，因此它只适合做日志记录，
     *       <b>绝不能用于安全校验</b>（如 IP 白名单）。</li>
     * </ol>
     */
    public static String getClientIp() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return UNKNOWN;
        }
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (StringUtils.hasText(ip) && !UNKNOWN.equalsIgnoreCase(ip)) {
                // X-Forwarded-For 可能是多级链路，取第一段
                int commaIndex = ip.indexOf(',');
                String realIp = commaIndex > 0 ? ip.substring(0, commaIndex) : ip;
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 获取 User-Agent */
    public static String getUserAgent() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        // 截断，防止超长 UA 撑爆数据库字段
        return ua != null && ua.length() > 100 ? ua.substring(0, 100) : ua;
    }

    /** 获取请求 URI */
    public static String getRequestUri() {
        HttpServletRequest request = getRequest();
        return request == null ? null : request.getRequestURI();
    }
}
