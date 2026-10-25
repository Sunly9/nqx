package com.nqx.controller.app;

import com.nqx.common.annotation.Log;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.AppLoginDTO;
import com.nqx.pojo.vo.app.AppLoginVO;
import com.nqx.pojo.vo.app.AppUserInfoVO;
import com.nqx.pojo.vo.app.AppUserVO;
import com.nqx.service.app.AppAuthService;
import com.nqx.service.satoken.StpAppUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端认证接口。
 *
 * <p>其中 {@code /app/auth/login} 在 {@code SaTokenConfig} 的免认证白名单中，
 * 其余三个需要登录态。
 *
 * <p><b>这里刻意没有 {@code @SaCheckPermission}。</b>
 * 小程序端没有 RBAC 模型 —— 它的权限边界是「只能操作自己的数据」，
 * 这由业务层从会话取当前用户 ID 来保证（见 {@code AppAuthService}），
 * 而不是靠权限码。给小程序端套一套形同虚设的权限注解只会造成"有权限控制"的假象。
 */
@Tag(name = "小程序端-认证", description = "微信登录、退出、获取/更新个人信息")
@RestController
@RequestMapping("/app/auth")
@RequiredArgsConstructor
public class AppAuthController {

    private final AppAuthService appAuthService;

    @Operation(summary = "微信登录 / 注册",
            description = "首次调用不带 userInfo，返回 status=new_user；带 userInfo 再调一次完成注册并登录")
    @Log(module = "小程序认证", operation = "用户登录")
    @PostMapping("/login")
    public Result<AppLoginVO> login(@RequestBody @Valid AppLoginDTO dto) {
        return Result.success(appAuthService.login(dto));
    }

    @Operation(summary = "退出登录")
    @Log(module = "小程序认证", operation = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpAppUtil.logout();
        return Result.success("已退出登录", null);
    }

    @Operation(summary = "获取当前用户信息",
            description = "返回体外层包了 userInfo 字段，与旧版前端的取值方式保持一致")
    @GetMapping("/info")
    public Result<AppUserInfoVO> info() {
        return Result.success(AppUserInfoVO.of(appAuthService.currentUser()));
    }

    @Operation(summary = "更新个人资料",
            description = "只允许修改昵称/头像/学院/实验室等字段；手机号需通过微信手机号校验流程单独更新")
    @Log(module = "用户中心", operation = "更新个人资料")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody AppUserVO profile) {
        appAuthService.updateProfile(profile);
        return Result.success("保存成功", null);
    }
}
