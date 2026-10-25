package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.points.AdminPointsQuery;
import com.nqx.pojo.entity.PointsRecord;
import com.nqx.pojo.vo.order.AdminPointsRecordVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 积分流水 Mapper。
 *
 * <p>本表是「只追加」的账本：只插入、不更新、不删除。
 *
 * <p>唯一索引 {@code uk_user_biz_type (user_id, biz_no, change_type)}
 * 是整套积分体系<b>防重复发放 / 防重复兑换的最终防线</b>。
 * 应用层的"先查有没有处理过"在并发下必然失效（两个线程会同时查到"没有"），
 * 只有数据库唯一约束才真正可靠 —— 重复插入会抛 {@code DuplicateKeyException}，
 * 由 {@code PointsService} 捕获并降级为幂等成功。
 *
 * <p>注意本表没有 {@code deleted} 列：账本记录不允许删除，
 * 写错了要用反向流水冲正，而不是删掉原记录。
 */
@Mapper
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    /**
     * 后台流水多条件检索（需要 JOIN app_user 取昵称，故手写 XML）。
     *
     * <p>SQL 见 {@code resources/mapper/PointsRecordMapper.xml}。
     */
    IPage<AdminPointsRecordVO> selectAdminPage(IPage<AdminPointsRecordVO> page,
                                               @Param("query") AdminPointsQuery query);
}
