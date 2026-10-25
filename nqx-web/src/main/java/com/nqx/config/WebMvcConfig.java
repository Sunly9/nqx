package com.nqx.config;

import com.nqx.common.constant.CommonConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置：跨域与上传文件静态映射。
 *
 * <p>认证拦截器不在这里注册，见 {@link SaTokenConfig} —— 职责分离，
 * 避免一个类同时管 CORS 和鉴权导致改一处影响另一处。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${nqx.upload.url-prefix:/files}")
    private String uploadUrlPrefix;

    @Value("${nqx.upload.local-path:./uploads}")
    private String uploadLocalPath;

    /**
     * 跨域配置。
     *
     * <p><b>为什么要用 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins("*")}？</b>
     * 当 {@code allowCredentials = true} 时，Spring 会拒绝 {@code allowedOrigins("*")}
     * 并抛出 {@code IllegalArgumentException}（浏览器规范也不允许
     * {@code Access-Control-Allow-Origin: *} 与凭据同时使用）。
     * {@code allowedOriginPatterns} 会回显请求方 Origin，是带凭据场景下的正确写法。
     *
     * <p>生产环境应收窄为具体域名白名单，例如
     * {@code .allowedOriginPatterns("https://admin.nqx.edu.cn")}。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                // 暴露自定义响应头，否则前端 JS 读不到 traceId，
                // 报错时无法把 traceId 反馈给后端定位
                .exposedHeaders(CommonConstants.TRACE_ID_HEADER, "Content-Disposition")
                .allowCredentials(true)
                // 预检请求缓存 1 小时，减少 OPTIONS 请求数量
                .maxAge(3600);
    }

    /**
     * 上传文件的静态资源映射。
     *
     * <p>把磁盘目录映射为可访问的 URL。使用 {@link Path#toUri()} 生成 {@code file:} URI，
     * 而不是手工拼接字符串 —— 因为 Windows 的路径分隔符是反斜杠，
     * 手工拼接会得到 {@code file:./uploads/} 这种无法解析的地址，
     * 且在含中文或空格的路径下必然出错。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadLocalPath).toAbsolutePath().normalize().toUri().toString();
        log.info("上传文件静态映射: {} -> {}", uploadUrlPrefix + "/**", location);

        registry.addResourceHandler(uploadUrlPrefix + "/**")
                .addResourceLocations(location);
    }
}
