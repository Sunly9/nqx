package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.app.AdminRepairQuery;
import com.nqx.pojo.entity.RepairApply;
import com.nqx.pojo.vo.app.AdminRepairApplyVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 维修申请 Mapper。
 *
 * <p>唯一索引 {@code uk_apply_no} 保证申请单号不重复。
 * 注意该索引必须包含 {@code deleted} 列 —— 否则逻辑删除后
 * 同一个单号会被永久占用，重新生成时会一直撞唯一键。
 * （这个问题在建表时已经踩过一次，见 docs/06 的踩坑记录。）
 *
 * <p>管理端的分页查询放在 XML 里（{@link #selectAdminPage}）：它需要 JOIN
 * {@code app_user} 取昵称、JOIN {@code biz_order} 取订单号，
 * 而 MyBatis-Plus 的 LambdaQuery 表达不了 JOIN。
 */
@Mapper
public interface RepairApplyMapper extends BaseMapper<RepairApply> {

    /**
     * 管理端维修工单分页。
     *
     * <p>参数用 {@code @Param("q")} 显式命名，XML 里才能写成 {@code q.status}。
     * 不加这个注解时单个对象参数也能按属性名访问，但条件一多就容易
     * 和别的参数混淆 —— 显式命名让 XML 里的来源一目了然。
     */
    IPage<AdminRepairApplyVO> selectAdminPage(IPage<AdminRepairApplyVO> page,
                                              @Param("q") AdminRepairQuery query);
}
