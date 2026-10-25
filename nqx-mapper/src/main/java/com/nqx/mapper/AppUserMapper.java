package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nqx.pojo.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 小程序用户 Mapper。
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    /**
     * 累加用户的回收统计（累计重量、累计碳减排）。
     *
     * <p><b>为什么必须用 {@code SET x = x + ?} 而不是先读再写？</b>
     * 先读再写会丢更新（lost update）：两个管理员同时确认两笔回收单时，
     * 都读到 {@code total_weight = 10}，各自加 1.5 后写回 11.5，
     * 结果少了 1.5 —— 而这两笔业务各自看起来都"成功"了。
     * {@code SET total_weight = total_weight + ?} 由数据库在行锁保护下完成，
     * 天然原子，不存在这个问题。
     *
     * <p>这与 {@code PointsAccountMapper.deductPoints} 是同一个思路：
     * <b>凡是"基于当前值做增减"的更新，都必须下推到 SQL 里完成。</b>
     */
    int addRecycleStats(@Param("userId") Long userId,
                        @Param("weight") BigDecimal weight,
                        @Param("carbon") BigDecimal carbon);
}
