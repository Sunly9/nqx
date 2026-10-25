package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysRole;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色返回体。
 */
@Data
public class SysRoleVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String roleCode;

    private String roleName;

    private String description;

    private Integer sort;

    /** 状态 0禁用 1启用 */
    private Integer status;

    private LocalDateTime createTime;

    /**
     * 该角色关联的管理员数量。
     *
     * <p>摆在列表里是为了让「为什么这个角色删不掉」一眼可见，
     * 而不是等管理员点了删除、吃了提示才知道下面还挂着人。
     */
    private Integer userCount = 0;

    public static SysRoleVO from(SysRole role) {
        SysRoleVO vo = new SysRoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setDescription(role.getDescription());
        vo.setSort(role.getSort());
        vo.setStatus(role.getStatus());
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }
}
