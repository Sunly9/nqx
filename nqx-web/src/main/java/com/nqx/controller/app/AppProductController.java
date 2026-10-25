package com.nqx.controller.app;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.product.ProductQuery;
import com.nqx.pojo.vo.app.ProductAppVO;
import com.nqx.pojo.vo.product.ProductVO;
import com.nqx.service.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端商品接口。
 *
 * <p>这几个接口<b>无需登录</b>（{@code /app/product/**} 已在
 * {@code SaTokenConfig} 的免认证白名单中），因为商品浏览是开放的。
 *
 * <p>但"无需登录"不等于"可以随意指定筛选条件"：
 * 服务端强制只返回上架商品，详见 {@code ProductService#appPage}。
 */
@Tag(name = "小程序端-商品", description = "商品浏览与详情（无需登录）")
@RestController
@RequestMapping("/app/product")
@RequiredArgsConstructor
public class AppProductController {

    private final ProductService productService;

    @Operation(summary = "商品分页浏览", description = "只能看到上架商品；金额单位为元（旧契约）")
    @GetMapping("/page")
    public Result<PageResult<ProductAppVO>> page(ProductQuery query) {
        // 转换为小程序端 VO：金额由分转元、字段名补上旧页面在用的 image/sales 等。
        // 旧的商城主页直接写 {{item.price}}，若沿用管理端的分，价格会放大 100 倍。
        return Result.success(productService.appPage(query).map(ProductAppVO::from));
    }

    @Operation(summary = "热门商品", description = "小程序首页推荐位")
    @GetMapping("/hot")
    public Result<List<ProductAppVO>> hot(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return Result.success(productService.hotProducts(limit).stream()
                .map(ProductAppVO::from)
                .toList());
    }

    @Operation(summary = "商品详情", description = "走 Redis 逻辑过期缓存")
    @GetMapping("/{id}")
    public Result<ProductAppVO> detail(@PathVariable Long id) {
        return Result.success(ProductAppVO.from(productService.detail(id)));
    }
}
