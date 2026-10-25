package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.AppUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户身份关联 Mapper。
 *
 * <p>这是替代旧版「users + procurement_users 两张表」设计的关键表：
 * 一个用户可以同时是回收方和采购方，身份体现在这里的多行记录中。
 *
 * <p>唯一索引 {@code uk_user_role_type (user_id, role_type)} 保证不会重复绑定，
 * 并发下即使应用层判断失效，数据库也会拦住。
 */
@Mapper
public interface AppUserRoleMapper extends BaseMapper<AppUserRole> {
}
