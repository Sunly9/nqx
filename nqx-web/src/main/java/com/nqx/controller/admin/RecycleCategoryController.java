package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.product.RecycleCategoryQuery;
import com.nqx.pojo.dto.product.RecycleCategorySaveDTO;
import com.nqx.pojo.vo.product.RecycleCategoryVO;
import com.nqx.service.product.RecycleCategoryService;
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

/**
 * 管理端回收品类接口。
 *
 * <p>{@code @SaCheckPermission} 显式带 {@code type = "admin"} 的原因见
 * {@code ProductController} 的说明：本项目管理端是自定义登录类型，
 * 不写 type 会去校验默认逻辑的会话，鉴权必然失败。
 */
@Tag(name = "管理端-回收品类", description = "回收品类的分页查询与增删改")
@RestController
@RequestMapping("/admin/recycle-category")
@RequiredArgsConstructor
public class RecycleCategoryController {

    private final RecycleCategoryService recycleCategoryService;

    @Operation(summary = "回收品类分页检索", description = "支持按名称模糊、状态筛选")
    @SaCheckPermission(value = "recycle:category:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<RecycleCategoryVO>> page(RecycleCategoryQuery query) {
        return Result.success(recycleCategoryService.page(query));
    }

    @Operation(summary = "新增回收品类")
    @Log(module = "回收品类管理", operation = "新增回收品类")
    @SaCheckPermission(value = "recycle:category:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid RecycleCategorySaveDTO dto) {
        return Result.success("新增成功", recycleCategoryService.create(dto));
    }

    @Operation(summary = "修改回收品类", description = "修改单位积分只影响之后的新订单，历史订单沿用明细里的积分快照")
    @Log(module = "回收品类管理", operation = "修改回收品类")
    @SaCheckPermission(value = "recycle:category:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid RecycleCategorySaveDTO dto) {
        recycleCategoryService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除回收品类", description = "已被订单明细引用时不允许删除，应改为禁用")
    @Log(module = "回收品类管理", operation = "删除回收品类")
    @SaCheckPermission(value = "recycle:category:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        recycleCategoryService.delete(id);
        return Result.success("删除成功", null);
    }
}
