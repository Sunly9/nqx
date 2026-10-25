package com.nqx.pojo.dto.system;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 给管理员分配角色入参。
 */
@Data
public class AssignRolesDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色 ID 集合。
     *
     * <p>允许为空集合，表示「收回该管理员的全部角色」——
     * 这是合法操作（临时停用某人权限），因此不能加 {@code @NotEmpty}。
     */
    private List<Long> roleIds;
}
