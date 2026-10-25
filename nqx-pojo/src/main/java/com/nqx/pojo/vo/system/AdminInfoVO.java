package com.nqx.pojo.vo.system;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 当前登录管理员信息。
 *
 * <p><b>注意这里没有任何敏感字段</b>：不含 password（即使已加密也不该返回）、
 * 不含 deleted、不含内部备注。VO 是数据离开系统的最后一道关口，
 * 一旦 Entity 被直接返回，日后再加敏感字段就会自动泄露给前端。
 */
@Data
public class AdminInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String realName;

    private String avatar;

    private String phone;

    private String email;

    /** 角色标识集合，如 ["SUPER_ADMIN"] */
    private List<String> roles;

    /**
     * 权限码集合。
     * 前端用它实现按钮级权限控制（{@code v-permission} 指令），
     * 后端则通过 {@code @SaCheckPermission} 做同一套权限码的二次校验 ——
     * <b>前端隐藏按钮只是体验优化，真正的安全边界永远在后端。</b>
     */
    private List<String> permissions;
}
