package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.system.AssignPermissionsDTO;
import com.nqx.pojo.dto.system.SysRoleQuery;
import com.nqx.pojo.dto.system.SysRoleSaveDTO;
import com.nqx.pojo.vo.system.SysRoleVO;
import com.nqx.service.system.SysRoleService;
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
 * 管理端 - 系统管理 / 角色管理接口。
 */
@Tag(name = "管理端-系统管理/角色", description = "角色 CRUD 与角色授权")
@RestController
@RequestMapping("/admin/system/role")
@RequiredArgsConstructor
public class AdminSystemRoleController {

    private final SysRoleService sysRoleService;

    @Operation(summary = "角色分页", description = "额外返回每个角色关联的管理员数量，便于判断能否删除")
    @SaCheckPermission(value = "sys:role:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<SysRoleVO>> page(SysRoleQuery query) {
        return Result.success(sysRoleService.page(query));
    }

    /**
     * 全部角色（不分页），供「分配角色」下拉使用。
     *
     * <p>路径是 {@code /list} 而不是 {@code /all}：与项目里其它「不分页列表」
     * 的命名保持一致，前端调用点一眼能分清哪个会分页、哪个不会。
     */
    @Operation(summary = "全部角色列表", description = "含已禁用角色，供下拉选择与解除关联")
    @SaCheckPermission(value = "sys:role:list", type = "admin")
    @GetMapping("/list")
    public Result<List<SysRoleVO>> list() {
        return Result.success(sysRoleService.list());
    }

    @Operation(summary = "新增角色", description = "角色标识唯一")
    @Log(module = "系统管理", operation = "新增角色")
    @SaCheckPermission(value = "sys:role:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid SysRoleSaveDTO dto) {
        return Result.success("新增成功", sysRoleService.create(dto));
    }

    @Operation(summary = "修改角色", description = "超级管理员角色的标识与启用状态受保护")
    @Log(module = "系统管理", operation = "修改角色")
    @SaCheckPermission(value = "sys:role:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid SysRoleSaveDTO dto) {
        sysRoleService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除角色", description = "超管角色不可删；角色下还有管理员时不可删")
    @Log(module = "系统管理", operation = "删除角色")
    @SaCheckPermission(value = "sys:role:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysRoleService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "查询角色已分配的权限 ID", description = "供权限树回填勾选状态")
    @SaCheckPermission(value = "sys:role:list", type = "admin")
    @GetMapping("/{id}/permissions")
    public Result<List<Long>> getPermissionIds(@PathVariable Long id) {
        return Result.success(sysRoleService.getPermissionIds(id));
    }

    @Operation(summary = "给角色分配权限", description = "全量覆盖：先删旧关联再插新关联，并立即失效相关管理员的权限缓存")
    @Log(module = "系统管理", operation = "分配角色权限")
    @SaCheckPermission(value = "sys:role:assign", type = "admin")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id,
                                          @RequestBody AssignPermissionsDTO dto) {
        sysRoleService.assignPermissions(id, dto.getPermissionIds());
        return Result.success("权限分配成功", null);
    }
}
