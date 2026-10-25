package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员 - 角色关联 Mapper。
 *
 * <p>该表是纯粹的关联表，无复杂查询需求，单表 CRUD 由 MyBatis-Plus 完全覆盖。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
