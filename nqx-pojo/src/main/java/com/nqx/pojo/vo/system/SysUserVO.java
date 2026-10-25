package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysUser;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理员列表 / 详情返回体。
 *
 * <p><b>安全边界：这里没有、也永远不要有 {@code password} 字段。</b>
 * 注释开头那句话不是形式主义 —— {@code SysUser} 实体直接序列化返回给前端，
 * 就会把 BCrypt 密文一并吐出去；虽然密文不可逆，但它给了攻击者离线爆破的素材，
 * 属于典型的「本不该离开数据库的数据」。所有对外暴露都必须经 VO 收窄。
 *
 * <p>{@code roleIds} 与 {@code roleNames} 是「联表得到」的字段：
 * 它们不在 {@code sys_user} 表里，由 Service 填充。
 * 列表页需要展示角色名，编辑页需要回填角色 ID，因此两者都给。
 */
@Data
public class SysUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String realName;

    private String phone;

    private String email;

    private String avatar;

    /** 状态 0禁用 1启用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    private String remark;

    private LocalDateTime createTime;

    /** 已分配的角色 ID 集合 */
    private List<Long> roleIds = new ArrayList<>();

    /** 已分配的角色名称集合，供列表页直接展示 */
    private List<String> roleNames = new ArrayList<>();

    public static SysUserVO from(SysUser user) {
        SysUserVO vo = new SysUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setRemark(user.getRemark());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
