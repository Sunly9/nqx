package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.system.AssignRolesDTO;
import com.nqx.pojo.dto.system.ResetPasswordDTO;
import com.nqx.pojo.dto.system.SysUserQuery;
import com.nqx.pojo.dto.system.SysUserSaveDTO;
import com.nqx.pojo.vo.system.SysUserVO;
import com.nqx.service.system.SysUserService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 系统管理 / 管理员管理接口。
 *
 * <p>每个方法上的 {@code @SaCheckPermission} 都必须显式写 {@code type = "admin"}：
 * 注解默认作用于「默认登录类型」，而管理端用的是自定义的 {@code admin} 类型
 * （见 {@code StpAdminLogic}），漏写会去查默认逻辑的会话并永远判为未登录。
 *
 * <p><b>权限码是「一码一动作」的：</b>重置密码、分配角色虽然都作用在同一个路径
 * {@code /user} 下，但它们用的是各自独立的权限码或与「修改管理员」共用，
 * 目的是让「能改资料的人」与「能重置密码的人」可以被拆成两种角色。
 * 这也是为什么重置密码没有复用 {@code SysUserSaveDTO} —— 入参收窄到只有密码一个字段，
 * 调用方就无法夹带其它字段越权。
 */
@Tag(name = "管理端-系统管理/管理员", description = "后台管理员的检索、增删改、启禁用、分配角色与重置密码")
@RestController
@RequestMapping("/admin/system/user")
@RequiredArgsConstructor
public class AdminSystemUserController {

    private final SysUserService sysUserService;

    @Operation(summary = "管理员分页", description = "支持按账号、姓名模糊匹配与状态精确筛选")
    @SaCheckPermission(value = "sys:user:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<SysUserVO>> page(SysUserQuery query) {
        return Result.success(sysUserService.page(query));
    }

    @Operation(summary = "新增管理员", description = "密码用 BCrypt 加密存储；账号唯一")
    @Log(module = "系统管理", operation = "新增管理员")
    @SaCheckPermission(value = "sys:user:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid SysUserSaveDTO dto) {
        return Result.success("新增成功", sysUserService.create(dto));
    }

    @Operation(summary = "修改管理员", description = "不修改账号、密码与状态，这三项各有专用入口")
    @Log(module = "系统管理", operation = "修改管理员")
    @SaCheckPermission(value = "sys:user:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid SysUserSaveDTO dto) {
        sysUserService.update(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "启用/禁用管理员",
            description = "禁用会同时注销该账号的会话；不允许禁用自己，也不允许禁用超级管理员")
    @Log(module = "系统管理", operation = "启用/禁用管理员")
    @SaCheckPermission(value = "sys:user:edit", type = "admin")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysUserService.changeStatus(id, status);
        return Result.success(status != null && status == 1 ? "已启用" : "已禁用", null);
    }

    @Operation(summary = "重置管理员密码", description = "密码长度至少 6 位，服务端用 BCrypt 重新加密")
    @Log(module = "系统管理", operation = "重置管理员密码", saveParam = false)
    @SaCheckPermission(value = "sys:user:resetPwd", type = "admin")
    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody @Valid ResetPasswordDTO dto) {
        sysUserService.resetPassword(id, dto.getPassword());
        return Result.success("密码已重置", null);
    }

    @Operation(summary = "分配管理员角色", description = "全量覆盖：以本次提交的角色集合为准")
    @Log(module = "系统管理", operation = "分配管理员角色")
    @SaCheckPermission(value = "sys:user:edit", type = "admin")
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody AssignRolesDTO dto) {
        sysUserService.assignRoles(id, dto.getRoleIds());
        return Result.success("角色分配成功", null);
    }

    @Operation(summary = "查询管理员已分配的角色 ID", description = "供「分配角色」弹窗回填勾选项")
    @SaCheckPermission(value = "sys:user:list", type = "admin")
    @GetMapping("/{id}/roles")
    public Result<List<Long>> getRoleIds(@PathVariable Long id) {
        return Result.success(sysUserService.getRoleIds(id));
    }

    @Operation(summary = "删除管理员", description = "逻辑删除，同时清理角色关联并注销其会话")
    @Log(module = "系统管理", operation = "删除管理员")
    @SaCheckPermission(value = "sys:user:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysUserService.delete(id);
        return Result.success("删除成功", null);
    }
}
