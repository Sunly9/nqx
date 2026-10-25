package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.system.SysPermissionSaveDTO;
import com.nqx.pojo.vo.system.SysPermissionVO;
import com.nqx.service.system.SysPermissionService;
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
 * 管理端 - 系统管理 / 权限菜单接口。
 *
 * <p><b>这一组接口的权限码风险最高。</b>它们能修改的 {@code perm_code} 正是
 * 其它所有接口 {@code @SaCheckPermission} 所依赖的字符串：把某个权限码改掉，
 * 会让所有持有该权限的角色瞬间失去对应功能；把菜单节点删掉，会让对应页面从
 * 所有管理员的侧边栏消失。因此权限码本身没有做成可"自我提权"的形式
 * （例如不允许通过本接口给自己授予权限，授权动作在角色接口里，且受
 * {@code sys:role:assign} 权限码约束）。
 */
@Tag(name = "管理端-系统管理/权限菜单", description = "权限树的查询与维护（目录/菜单/按钮）")
@RestController
@RequestMapping("/admin/system/permission")
@RequiredArgsConstructor
public class AdminSystemPermissionController {

    private final SysPermissionService sysPermissionService;

    @Operation(summary = "权限树", description = "返回全部节点（含按钮、隐藏与禁用），供 tree-table 与角色授权树使用")
    @SaCheckPermission(value = "sys:perm:list", type = "admin")
    @GetMapping("/tree")
    public Result<List<SysPermissionVO>> tree() {
        return Result.success(sysPermissionService.tree());
    }

    @Operation(summary = "新增权限节点", description = "层级约束：目录挂在根或目录下，菜单挂在目录下，按钮挂在菜单下")
    @Log(module = "系统管理", operation = "新增权限节点")
    @SaCheckPermission(value = "sys:perm:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid SysPermissionSaveDTO dto) {
        return Result.success("新增成功", sysPermissionService.create(dto));
    }

    @Operation(summary = "修改权限节点", description = "不允许把节点移动到自身或其子节点下")
    @Log(module = "系统管理", operation = "修改权限节点")
    @SaCheckPermission(value = "sys:perm:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid SysPermissionSaveDTO dto) {
        sysPermissionService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除权限节点", description = "有子节点时拒绝；同时清理角色权限关联")
    @Log(module = "系统管理", operation = "删除权限节点")
    @SaCheckPermission(value = "sys:perm:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysPermissionService.delete(id);
        return Result.success("删除成功", null);
    }
}
