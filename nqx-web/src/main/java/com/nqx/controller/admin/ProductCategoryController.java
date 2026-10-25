package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.product.CategorySaveDTO;
import com.nqx.pojo.vo.product.CategoryVO;
import com.nqx.service.product.ProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端商品分类接口。
 *
 * <p>与 {@code ProductController} 一样，每个方法上的 {@code @SaCheckPermission}
 * 都必须显式写 {@code type = "admin"}：Sa-Token 的注解默认作用于「默认登录类型」，
 * 而本项目管理端用的是自定义的 {@code admin} 类型（见 {@code StpAdminLogic}）。
 * 漏写 type 会导致鉴权去查默认逻辑的会话，管理员永远被判为无权限。
 */
@Tag(name = "管理端-商品分类", description = "商品分类树的查询与增删改")
@RestController
@RequestMapping("/admin/product/category")
@RequiredArgsConstructor
public class ProductCategoryController {

    private final ProductCategoryService productCategoryService;

    @Operation(summary = "分类树", description = "只返回启用且未删除的分类，已按 sort 排好序")
    @SaCheckPermission(value = "product:category:list", type = "admin")
    @GetMapping("/tree")
    public Result<List<CategoryVO>> tree() {
        return Result.success(productCategoryService.tree());
    }

    @Operation(summary = "新增分类", description = "parentId 传 0 表示根分类")
    @Log(module = "商品管理", operation = "新增商品分类")
    @SaCheckPermission(value = "product:category:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid CategorySaveDTO dto) {
        return Result.success("新增成功", productCategoryService.create(dto));
    }

    @Operation(summary = "修改分类", description = "不能把分类移动到它自己的子分类下面")
    @Log(module = "商品管理", operation = "修改商品分类")
    @SaCheckPermission(value = "product:category:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid CategorySaveDTO dto) {
        productCategoryService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除分类", description = "存在子分类或已被商品引用时不允许删除")
    @Log(module = "商品管理", operation = "删除商品分类")
    @SaCheckPermission(value = "product:category:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productCategoryService.delete(id);
        return Result.success("删除成功", null);
    }
}
