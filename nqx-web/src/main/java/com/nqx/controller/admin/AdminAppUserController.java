package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.AppUserQuery;
import com.nqx.pojo.vo.app.AdminAppUserVO;
import com.nqx.service.app.AppUserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 用户管理 / 小程序用户。
 *
 * <p>这些是**平台服务的用户**（回收方 / 采购方），不是后台管理员。
 * 后台管理员的增删改在 {@code /admin/system/user} 下。
 * 两者刻意分成两套接口与两套权限码：混淆它们会出现
 * "以为在改一个普通用户，实际把管理员禁用掉"这类事故。
 *
 * <p>每个方法都必须显式写 {@code type = "admin"}：注解默认作用于
 * 「默认登录类型」，而管理端用自定义的 {@code admin} 类型（{@code StpAdminLogic}）。
 */
@Tag(name = "管理端-用户管理/小程序用户", description = "小程序用户的检索与启用/禁用")
@RestController
@RequestMapping("/admin/app-user")
@RequiredArgsConstructor
public class AdminAppUserController {

    private final AppUserAdminService appUserAdminService;

    @Operation(summary = "小程序用户分页",
            description = "支持昵称/手机号/学院模糊搜索、状态筛选、注册时间区间")
    @GetMapping("/page")
    @SaCheckPermission(value = "app:user:list", type = "admin")
    public Result<PageResult<AdminAppUserVO>> page(AppUserQuery query) {
        return Result.success(appUserAdminService.page(query));
    }

    @Operation(summary = "启用/禁用小程序用户",
            description = "禁用会同时注销该用户的登录会话，使其立刻失效")
    @Log(module = "用户管理", operation = "启用/禁用小程序用户")
    @PutMapping("/{id}/status")
    @SaCheckPermission(value = "app:user:edit", type = "admin")
    public Result<Void> changeStatus(@PathVariable Long id,
                                     @RequestParam Integer status) {
        appUserAdminService.changeStatus(id, status);
        return Result.success(status == 1 ? "已启用" : "已禁用", null);
    }
}
