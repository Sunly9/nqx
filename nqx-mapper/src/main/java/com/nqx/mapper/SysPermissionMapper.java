package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 后台权限 Mapper（目录 / 菜单 / 按钮 三合一树）。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询某管理员的全部权限码。
     *
     * <p>返回内容包含菜单与按钮的 {@code perm_code}（如 {@code order:recycle:audit}），
     * 登录时写入 Sa-Token 会话，供 {@code @SaCheckPermission} 与前端 {@code v-permission} 使用。
     *
     * <p>SQL 位于 {@code resources/mapper/SysPermissionMapper.xml}。
     */
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询某管理员可见的权限节点（目录 + 菜单，不含按钮），用于构建前端动态菜单树。
     *
     * <p>按钮类型（{@code perm_type = 3}）不参与菜单渲染，
     * 但其 {@code perm_code} 已由上面的方法提供，用于按钮级鉴权。
     */
    List<SysPermission> selectMenusByUserId(@Param("userId") Long userId);

    /**
     * 查询全部启用的权限节点，供「角色分配权限」页面渲染完整权限树。
     */
    List<SysPermission> selectAllEnabled();
}
