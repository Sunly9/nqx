package com.nqx.controller.admin;

import com.nqx.common.annotation.Idempotent;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.product.ProductQuery;
import com.nqx.pojo.dto.product.ProductSaveDTO;
import com.nqx.pojo.vo.product.ProductDetailVO;
import com.nqx.pojo.vo.product.ProductVO;
import com.nqx.service.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端商品接口。
 *
 * <p>每个方法上的 {@code @SaCheckPermission} 都必须显式写 {@code type = "admin"}：
 * Sa-Token 的注解默认作用于「默认登录类型」，而本项目管理端用的是自定义的
 * {@code admin} 类型（见 {@code StpAdminLogic}）。漏写 type 会导致鉴权
 * 去查默认逻辑的会话，永远判为未登录。
 */
@Tag(name = "管理端-商品管理", description = "商品的多条件检索、增删改、上下架")
@RestController
@RequestMapping("/admin/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "商品分页检索",
            description = "支持按名称模糊、分类、类型、状态、热门、价格区间、积分区间、库存预警等多条件组合查询")
    @SaCheckPermission(value = "product:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<ProductVO>> page(ProductQuery query) {
        return Result.success(productService.page(query));
    }

    @Operation(summary = "商品详情", description = "走 Redis 逻辑过期缓存")
    @SaCheckPermission(value = "product:list", type = "admin")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.detail(id));
    }

    @Operation(summary = "新增商品", description = "新增后默认下架，需单独调用上下架接口。已加接口幂等，5 秒内重复提交会被拦截")
    @Log(module = "商品管理", operation = "新增商品")
    @Idempotent(prefix = "product:create", expire = 5, message = "商品正在创建中，请勿重复提交")
    @SaCheckPermission(value = "product:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid ProductSaveDTO dto) {
        return Result.success("新增成功", productService.create(dto));
    }

    @Operation(summary = "修改商品", description = "会清除该商品的详情缓存")
    @Log(module = "商品管理", operation = "修改商品")
    @SaCheckPermission(value = "product:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid ProductSaveDTO dto) {
        productService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除商品", description = "逻辑删除，历史订单仍可引用")
    @Log(module = "商品管理", operation = "删除商品")
    @SaCheckPermission(value = "product:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "商品上下架")
    @Log(module = "商品管理", operation = "商品上下架")
    @SaCheckPermission(value = "product:changeStatus", type = "admin")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.changeStatus(id, status);
        return Result.success(status == 1 ? "已上架" : "已下架", null);
    }
}
