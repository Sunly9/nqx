package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 后台角色 Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询某管理员拥有的角色标识集合（如 {@code [SUPER_ADMIN]}）。
     *
     * <p>仅在登录时调用一次，结果会被写入 Sa-Token 会话，
     * 后续的 {@code hasRole} 判断直接读会话，不会再查库。
     *
     * @param userId 管理员 ID
     */
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
