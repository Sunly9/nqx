package com.nqx.controller.admin;

import com.nqx.common.annotation.Log;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.system.AdminLoginDTO;
import com.nqx.pojo.vo.system.AdminInfoVO;
import com.nqx.pojo.vo.system.AdminLoginVO;
import com.nqx.pojo.vo.system.MenuVO;
import com.nqx.service.satoken.StpAdminUtil;
import com.nqx.service.system.AdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端认证接口。
 *
 * <p>注意 {@code /admin/auth/login} 已在 {@code SaTokenConfig} 的免认证白名单中，
 * 其余三个接口都需要登录态。
 */
@Tag(name = "管理端-认证", description = "管理员登录、注销、获取个人信息与动态菜单")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @Operation(summary = "管理员登录", description = "账号密码登录，返回 token 与权限码集合")
    @Log(module = "认证管理", operation = "管理员登录")
    @PostMapping("/login")
    public Result<AdminLoginVO> login(@RequestBody @Valid AdminLoginDTO dto) {
        return Result.success("登录成功", adminAuthService.login(dto));
    }

    @Operation(summary = "退出登录", description = "注销当前会话")
    @Log(module = "认证管理", operation = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpAdminUtil.logout();
        return Result.success("已退出登录", null);
    }

    @Operation(summary = "获取当前管理员信息", description = "含角色标识与权限码，供前端做按钮级权限控制")
    @GetMapping("/info")
    public Result<AdminInfoVO> info() {
        return Result.success(adminAuthService.currentAdminInfo());
    }

    @Operation(summary = "获取动态菜单", description = "按当前管理员的权限返回菜单树，前端据此 addRoute 动态注册路由")
    @GetMapping("/menus")
    public Result<List<MenuVO>> menus() {
        return Result.success(adminAuthService.currentMenus());
    }
}
