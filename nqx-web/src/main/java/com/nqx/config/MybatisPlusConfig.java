package com.nqx.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p><b>插件顺序不能随意调换</b>。MyBatis-Plus 官方建议的顺序是：
 * 「多租户/动态表名 → 分页/乐观锁 → SQL 性能规范/防全表更新删除」。
 * 因为拦截器是链式执行的，分页必须在乐观锁之前插入 count 语句，
 * 而防全表更新应当在最后一道关口拦截已经拼好的 SQL。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ------------------------------------------------------------------
        // 1. 分页插件
        //    注意：依赖 mybatis-plus-jsqlparser，MyBatis-Plus 3.5.9 起
        //    JSQLParser 被拆为独立依赖，缺失会在运行期抛 NoClassDefFoundError。
        // ------------------------------------------------------------------
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数限制：防止前端传 size=999999 把数据库拖垮
        pagination.setMaxLimit(100L);
        // overflow = false：页码超过总页数时返回空列表，而不是回到第一页。
        // 回到第一页会让前端分页器出现"点第 99 页却显示第 1 页数据"的诡异现象。
        pagination.setOverflow(false);
        // 优化 count 语句：当查询没有 group by / distinct 等复杂结构时，
        // 自动把 select 字段替换成 count(*)，显著减少扫描量
        pagination.setOptimizeJoin(true);
        interceptor.addInnerInterceptor(pagination);

        // ------------------------------------------------------------------
        // 2. 乐观锁插件
        //    配合实体上的 @Version 字段生效。
        //    本项目用于订单（biz_order.version）和积分账户（points_account.version）。
        // ------------------------------------------------------------------
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // ------------------------------------------------------------------
        // 3. 防全表更新与删除
        //    拦截没有 WHERE 条件的 UPDATE/DELETE，直接抛异常。
        //    这是对「误执行 UPDATE user SET status = 0」这类事故的最后一道防线。
        // ------------------------------------------------------------------
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
