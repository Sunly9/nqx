package com.nqx.pojo.dto.system;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 给角色分配权限入参。
 */
@Data
public class AssignPermissionsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 权限 ID 集合。
     *
     * <p>与分配角色同理，允许为空（把角色清空成无权限）。
     */
    private List<Long> permissionIds;
}
