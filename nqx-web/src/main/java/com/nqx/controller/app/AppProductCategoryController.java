package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.pojo.vo.product.CategoryVO;
import com.nqx.service.product.ProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端商品分类接口。
 *
 * <p><b>无需登录</b>：{@code /app/product/**} 已在 {@code SaTokenConfig} 的免认证
 * 白名单中，因为小程序首页的分类导航必须能在未授权（用户还没点登录）时展示。
 *
 * <p>因此这里<b>不能</b>加 {@code @SaCheckPermission}：注解鉴权会去查登录会话，
 * 未登录请求会直接 401，整个首页分类导航挂掉。
 *
 * <p>与 {@code AppProductController} 一致，返回的数据由服务端强制只取「启用」状态，
 * 不信任前端传参。
 */
@Tag(name = "小程序端-商品分类", description = "分类树浏览（无需登录）")
@RestController
@RequestMapping("/app/product/category")
@RequiredArgsConstructor
public class AppProductCategoryController {

    private final ProductCategoryService productCategoryService;

    @Operation(summary = "分类树", description = "只返回启用且未删除的分类")
    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        return Result.success(productCategoryService.tree());
    }
}
