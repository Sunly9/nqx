package com.nqx.pojo.dto.system;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员分页检索条件。
 *
 * <p>三个条件全部可选，前端传哪个就拼哪个 WHERE，与 {@code ProductQuery} 的约定一致。
 * 这里没有做「按角色筛选」：管理员的角色来自 {@code sys_user_role} 中间表，
 * 要支持它就得在管理员列表的 SQL 上再套一层子查询/EXISTS，
 * 而实际运维中「先按账号或姓名定位到人」已经足够，因此先不引入这个复杂度。
 */
@Data
public class SysUserQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 登录账号，模糊匹配 */
    private String username;

    /** 姓名，模糊匹配 */
    private String realName;

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
