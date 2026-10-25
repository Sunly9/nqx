package com.nqx.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import com.nqx.service.satoken.StpAdminUtil;
import com.nqx.service.satoken.StpAppUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 认证配置：注册双端 StpLogic + 全局登录拦截。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 无需认证的基础设施路径 */
    private static final String[] EXCLUDE_PATTERNS = {
            // 接口文档（springdoc 4 个入口都要放开，否则文档页白屏）
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            // 健康检查（Docker healthcheck 不带 token）
            "/actuator/**",
            // 上传文件的静态访问
            "/files/**",
            // 错误转发路径：若拦在这里，Spring 处理异常时的二次转发会被判为未登录，
            // 导致真正的错误信息被 401 覆盖，极难排查
            "/error",
            "/favicon.ico"
    };

    /**
     * 注册两套 StpLogic。
     *
     * <p>必须在容器启动完成前执行，否则第一个请求进来时注解鉴权会因找不到
     * {@code type = "admin"} 对应的 StpLogic 而失败。
     * {@code @PostConstruct} 在 Bean 初始化后、Web 容器开始接收请求前执行，时机合适。
     */
    @PostConstruct
    public void registerStpLogic() {
        SaManager.putStpLogic(StpAdminUtil.stpLogic);
        SaManager.putStpLogic(StpAppUtil.stpLogic);
    }

    /**
     * 全局认证拦截器。
     *
     * <p>两条路由规则按路径前缀区分归属端：
     * <ul>
     *   <li>{@code /admin/**} —— 校验管理端登录态；</li>
     *   <li>{@code /app/**} —— 校验小程序端登录态，商品浏览与回收品类浏览为公开接口。</li>
     * </ul>
     *
     * <p><b>关键点</b>：哪一端用哪个 StpLogic 校验，是这套双端体系安全性的核心。
     * 若此处误写成「统一用 StpAdminUtil 校验 /app/**」，小程序用户就永远登录不上；
     * 反之若 /admin/** 用 StpAppUtil 校验，则小程序用户可能访问管理端接口。
     *
     * <p>{@code isAnnotation(true)} 开启注解鉴权，使 {@code @SaCheckPermission}、
     * {@code @SaCheckRole} 生效。注意注解默认作用于 Sa-Token 的「默认」登录类型，
     * 因此管理端接口上的注解必须显式写 {@code type = "admin"}。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {

                    // ---------------- 管理端 ----------------
                    SaRouter.match("/admin/**")
                            .notMatch("/admin/auth/login")
                            .check(r -> StpAdminUtil.checkLogin());

                    // ---------------- 小程序端 ----------------
                    // 登录接口、商品浏览、回收品类浏览、内容（小贴士/FAQ/公告/配置）对未登录用户开放。
                    // 内容接口必须放行：这些是进入小程序首页就要展示的东西，
                    // 不能等用户登录后才可见。
                    SaRouter.match("/app/**")
                            .notMatch("/app/auth/login",
                                    "/app/product/**",
                                    "/app/recycle-category/**",
                                    "/app/content/**")
                            .check(r -> StpAppUtil.checkLogin());

                }).isAnnotation(true))
                .addPathPatterns("/**")
                .excludePathPatterns(EXCLUDE_PATTERNS);
    }
}
