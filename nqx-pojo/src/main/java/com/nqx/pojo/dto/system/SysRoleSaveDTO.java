package com.nqx.pojo.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色新增 / 修改入参。
 */
@Data
public class SysRoleSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空 */
    private Long id;

    /**
     * 角色标识，如 {@code OPERATOR}。
     *
     * <p>限制成大写字母 + 下划线，是因为它会被写进 Sa-Token 会话，
     * 并被 {@code @SaCheckRole} 与前端 {@code hasRole} 按字面量比较 ——
     * 一旦出现大小写不一致（{@code operator} / {@code OPERATOR}），
     * 鉴权会静默失败，且报错信息只会说「无权限」，排查成本很高。
     * 在入口处收紧格式，比事后排查划算得多。
     */
    @NotBlank(message = "请输入角色标识")
    @Size(max = 50, message = "角色标识不能超过 50 位")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "角色标识只能包含大写字母、数字与下划线，且以字母开头")
    private String roleCode;

    @NotBlank(message = "请输入角色名称")
    @Size(max = 50, message = "角色名称不能超过 50 个字符")
    private String roleName;

    @Size(max = 200, message = "描述不能超过 200 个字符")
    private String description;

    /** 排序号，越小越靠前。为空时按 0 处理 */
    private Integer sort;

    /** 状态 0禁用 1启用，为空时按启用处理 */
    private Integer status;
}
