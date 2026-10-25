package com.nqx.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 前端（Vue SPA）路由回退。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>管理后台用的是 Vue Router 的 **history 模式**（`createWebHistory()`），
 * 也就是说地址栏里是 {@code /order/recycle} 这种"真路径"，不是 {@code /#/order/recycle}。
 *
 * <p>问题是：这些路径在服务器上**并不存在对应的文件**。
 * 用户直接在地址栏访问、或按 F5 刷新，请求会打到后端，然后得到 404 ——
 * 而实际上前端路由是能渲染的，只是浏览器没拿到 index.html。
 *
 * <p>业界标准做法是「回退」：任何看起来像前端路由的 GET 请求，都返回 index.html，
 * 剩下的交给前端路由自己解析。
 *
 * <h2>为什么不用通配 {@code /**} 的资源处理器</h2>
 *
 * <p>曾经考虑过给 {@code /**} 注册一个 PathResourceResolver 做回退。
 * 但那样会**把 API 的 404 也变成 index.html** ——
 * 前端拿到的是一段 HTML，而不是 {@code {"code":404,"msg":"资源不存在"}}，
 * 排查时会完全失去方向（这个项目已经因为"404 被伪装"吃过一次亏，见 docs/06 第 39 条）。
 *
 * <p>所以这里用**显式的前缀白名单**排除 API，只回退"看起来像页面"的请求。
 *
 * <h2>怎么区分"页面请求"和"资源请求"</h2>
 *
 * <ul>
 *   <li>方法必须是 GET（POST/PUT/DELETE 一律不碰）；</li>
 *   <li>路径不能以 API 前缀开头（{@code /admin/}、{@code /app/}、{@code /files/} 等）；</li>
 *   <li>路径最后一段**不能含点** —— 有扩展名的是静态资源
 *       （{@code /assets/index-abc123.js}、{@code /favicon.ico}），不该回退。</li>
 * </ul>
 *
 * <p>这条规则简单但够用：Vite 产物的文件名都带 hash 和扩展名，
 * 而前端路由（{@code /dashboard}、{@code /order/recycle}）都不含点。
 *
 * <h2>什么时候生效</h2>
 *
 * <p>只在 {@code nqx.web.spa-enabled=true} 时注册（生产/容器）。
 * 本地开发时前端由 vite dev server 提供，后端不该掺和，
 * 所以默认关闭 —— 否则本地访问 {@code /dashboard} 会得到一个空的 index.html 404。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nqx.web.spa-enabled", havingValue = "true")
public class SpaFallbackFilter extends OncePerRequestFilter {

    /**
     * 这些前缀下的请求是「接口」，绝不回退。
     *
     * <p>宁可漏一个、也不要多回退一个：漏了只是 404 页面打不开（能立刻发现），
     * 多回退了则是**接口返回 HTML**（静默、极难排查）。
     */
    private static final List<String> API_PREFIXES = List.of(
            "/admin/",
            "/app/",
            "/files/",
            "/actuator",
            "/v3/api-docs",
            "/swagger-ui",
            "/webjars/",
            "/error"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!shouldForwardToIndex(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 转发到 index.html，交给前端路由解析
        request.getRequestDispatcher("/index.html").forward(request, response);
    }

    private boolean shouldForwardToIndex(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.isEmpty()) {
            return false;
        }

        for (String prefix : API_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }

        // 最后一段含点 → 有扩展名 → 静态资源，不回退
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return lastSegment.indexOf('.') < 0;
    }
}
