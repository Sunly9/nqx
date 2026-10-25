package com.nqx.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc-openapi）。
 *
 * <p><b>为什么不用 Knife4j？</b> 它在 Spring Boot 3.5 上存在硬性不兼容：
 * 其 parent 把 springdoc 锁死在 2.3.0，早于 Spring Framework 6.2，
 * 运行期会抛 {@code NoSuchMethodError: ControllerAdviceBean.<init>}。
 * 且 Knife4j 4.5.0 是末版，官方无修复版本。详见 docs/04-依赖版本锁定.md。
 *
 * <p>访问地址：{@code /swagger-ui.html}，OpenAPI JSON：{@code /v3/api-docs}
 */
@Configuration
public class OpenApiConfig {

    /** 认证方案名称，与下方 securityScheme 的 key 保持一致 */
    private static final String SECURITY_SCHEME_NAME = "Authorization";

    @Bean
    public OpenAPI nqxOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("纽启新生 · 高校实验室纽扣电池回收与采购平台")
                        .version("1.0.0")
                        .description("""
                                面向高校实验室的纽扣电池耗材回收与采购管理平台。

                                **接口分组**
                                - 管理后台：`/api/admin/**`，需管理员登录，受 RBAC 权限码控制
                                - 小程序端：`/api/app/**`，需小程序用户登录（商品浏览类接口除外）

                                **认证方式**
                                先调用登录接口获取 token，再点击右上角 Authorize 按钮填入 token
                                （Sa-Token 会自动识别并剥离 `Bearer ` 前缀）。

                                **统一返回体**
                                所有接口均返回 `{ code, msg, data, timestamp }`，
                                HTTP 状态码恒为 200，业务成败由 `code` 表达（200 为成功）。
                                """)
                        .contact(new Contact().name("NQX"))
                        .license(new License().name("MIT")))
                // 全局启用认证：让 Swagger UI 的 Authorize 按钮对所有接口生效
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("登录接口返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * 按端分组。分组后 Swagger 页面顶部的下拉框可以快速切换，
     * 避免几十个接口混在一页里难以查找。
     */
    @Bean
    public GroupedOpenApi adminApiGroup() {
        return GroupedOpenApi.builder()
                .group("01-管理后台")
                .pathsToMatch("/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi appApiGroup() {
        return GroupedOpenApi.builder()
                .group("02-小程序端")
                .pathsToMatch("/app/**")
                .build();
    }
}
