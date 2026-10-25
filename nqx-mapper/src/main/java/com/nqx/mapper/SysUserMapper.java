package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 后台管理员 Mapper。
 *
 * <p>继承 {@code BaseMapper} 后即获得单表 CRUD、条件构造器查询、分页等能力，
 * 无需再手写这些重复 SQL —— 这正是引入 MyBatis-Plus 的收益所在。
 *
 * <p>需要复杂查询（多条件组合检索、跨表聚合）时，在本接口声明方法
 * 并在 {@code resources/mapper/*.xml} 中手写 SQL，保持 SQL 可控可优化。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
