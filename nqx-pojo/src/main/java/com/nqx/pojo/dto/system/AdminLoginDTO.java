package com.nqx.pojo.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员登录入参。
 */
@Data
public class AdminLoginDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "请输入账号")
    @Size(max = 50, message = "账号长度不能超过 50 位")
    private String username;

    // 只校验非空，不限制长度：限制密码长度会泄露"系统密码策略"这一信息，
    // 且过短的输入在 BCrypt 校验阶段自然会失败
    @NotBlank(message = "请输入密码")
    private String password;
}
