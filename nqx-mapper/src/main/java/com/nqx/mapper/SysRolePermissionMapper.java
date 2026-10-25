package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 - 权限关联 Mapper。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
