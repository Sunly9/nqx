package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.pojo.vo.product.RecycleCategoryVO;
import com.nqx.service.product.RecycleCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端回收品类接口。
 *
 * <p><b>无需登录</b>：{@code /app/recycle-category/**} 已在 {@code SaTokenConfig}
 * 的免认证白名单中 —— 小程序要先把「能回收什么、每节多少积分」展示给用户，
 * 用户才可能去授权登录。
 *
 * <p>因此这里<b>不能</b>加 {@code @SaCheckPermission}，否则未登录请求会直接 401。
 * 返回结果由服务端强制只取启用中的品类，详见
 * {@code RecycleCategoryService#listEnabled()}。
 */
@Tag(name = "小程序端-回收品类", description = "回收品类列表（无需登录）")
@RestController
@RequestMapping("/app/recycle-category")
@RequiredArgsConstructor
public class AppRecycleCategoryController {

    private final RecycleCategoryService recycleCategoryService;

    @Operation(summary = "回收品类列表", description = "只返回启用中的品类，按 sort 升序")
    @GetMapping("/list")
    public Result<List<RecycleCategoryVO>> list() {
        return Result.success(recycleCategoryService.listEnabled());
    }
}
