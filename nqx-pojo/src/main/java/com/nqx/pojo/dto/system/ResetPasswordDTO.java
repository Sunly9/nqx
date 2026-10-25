package com.nqx.pojo.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 重置管理员密码入参。
 *
 * <p>单独建一个 DTO 而不是复用 {@code SysUserSaveDTO}：
 * 重置密码是一个「只改密码、且由管理员代替他人执行」的动作，
 * 若复用保存 DTO，调用方就可能夹带 status、realName 等字段一起提交，
 * 从而绕过「编辑」与「重置密码」两个权限码的区分。
 * 入参结构收窄到只有一个字段，是让越权在结构上不可能发生。
 */
@Data
public class ResetPasswordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // 6 位是下限，64 位是 BCrypt 的实际有效上限（超出部分会被算法静默忽略）
    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
    private String password;
}
