package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.order.AdminOrderQuery;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.vo.order.AdminOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单主表 Mapper。
 *
 * <p>小程序端的「我的订单」用条件构造器即可（条件固定：user_id + order_type + status），
 * 但后台的多条件检索条件数量随前端传参动态变化，且需要 JOIN {@code app_user}
 * 取用户昵称，因此这一处手写 XML。
 */
@Mapper
public interface BizOrderMapper extends BaseMapper<BizOrder> {

    /**
     * 后台订单多条件分页检索。
     *
     * <p>SQL 见 {@code resources/mapper/BizOrderMapper.xml}，使用
     * {@code <where>} + {@code <if>} 动态拼接。
     *
     * <p>注意返回类型是 {@link AdminOrderVO} 而不是实体 ——
     * 因为它多了一个来自 JOIN 的 {@code nickname} 字段。
     */
    IPage<AdminOrderVO> selectAdminPage(IPage<AdminOrderVO> page, @Param("query") AdminOrderQuery query);
}
