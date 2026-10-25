package com.nqx.pojo.dto.system;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色分页检索条件。
 */
@Data
public class SysRoleQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色标识，模糊匹配，如 SUPER */
    private String roleCode;

    /** 角色名称，模糊匹配 */
    private String roleName;

    /** 状态 0禁用 1启用 */
    private Integer status;

    private Long pageNum;

    private Long pageSize;

    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
