package com.nqx.pojo.dto.system;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员新增 / 修改入参。
 *
 * <p><b>为什么新增与修改共用一个 DTO？</b>
 * 字段完全重合（靠 id 是否为空区分动作），与 {@code ProductSaveDTO} 的处理方式一致。
 *
 * <p><b>但要注意「允许出现的字段」并不等于「允许修改的字段」</b>：
 * {@code username} 与 {@code password} 只对新增有意义，
 * 修改接口会在 Service 里显式忽略它们（详见 {@code SysUserService#update}），
 * 原因是账号是登录凭据、密码是安全凭据，二者都不该在「编辑资料」这个动作里被顺手改掉。
 */
@Data
public class SysUserSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空 */
    private Long id;

    @NotBlank(message = "请输入登录账号")
    @Size(min = 3, max = 50, message = "登录账号长度需在 3~50 位之间")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "登录账号只能包含字母、数字、下划线与短横线")
    private String username;

    /**
     * 密码，仅新增时使用。
     *
     * <p>最小值 6 位在 DTO 上校验而不是只在 Service 里校验 ——
     * 这样前端拿到的报错文案由统一的参数校验器给出，不必再写一遍重复的判断。
     * 修改密码请走独立的「重置密码」接口。
     */
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
    private String password;

    @NotBlank(message = "请输入姓名")
    @Size(max = 50, message = "姓名不能超过 50 个字符")
    private String realName;

    @Size(max = 20, message = "手机号长度不能超过 20 位")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    private String email;

    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;

    /** 状态 0禁用 1启用，为空时新增默认启用 */
    private Integer status;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
