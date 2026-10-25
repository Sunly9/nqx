package com.nqx.service.points;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.PointsAccountMapper;
import com.nqx.mapper.PointsRecordMapper;
import com.nqx.pojo.dto.points.AdminPointsQuery;
import com.nqx.pojo.dto.points.PointsAdjustDTO;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.order.AdminPointsAccountVO;
import com.nqx.pojo.vo.order.AdminPointsRecordVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 后台积分管理服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPointsService {

    /** 幂等键前缀，让管理员调整的流水在库里一眼可辨 */
    private static final String ADJUST_BIZ_PREFIX = "ADJUST-";

    private final PointsService pointsService;
    private final PointsRecordMapper pointsRecordMapper;
    private final PointsAccountMapper pointsAccountMapper;

    // ==================== 查询 ====================

    /**
     * 积分流水多条件检索。
     *
     * <p>类型的中文说明在这里由枚举补齐（见 {@code fillChangeTypeText} 的说明），
     * 而不是在 SQL 里写 {@code CASE WHEN}。
     */
    public PageResult<AdminPointsRecordVO> pageRecords(AdminPointsQuery query) {
        Page<AdminPointsRecordVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<AdminPointsRecordVO> result = pointsRecordMapper.selectAdminPage(page, query);
        result.getRecords().forEach(AdminPointsRecordVO::fillChangeTypeText);
        return PageUtil.of(result);
    }

    /**
     * 导出用：取符合条件的全部流水（上限 {@code maxRows} 条）。
     *
     * <p>与 {@link #pageRecords} 用同一条 Mapper SQL，只是关掉计数并放大页大小 ——
     * 两处筛选条件必须完全一致，否则会出现"列表和导出条数对不上"的问题。
     *
     * <p>第三参数 {@code false} 是 {@code searchCount}：导出不需要总数，
     * 省掉一条 {@code COUNT(*)}。
     */
    public List<AdminPointsRecordVO> exportRecords(AdminPointsQuery query, int maxRows) {
        Page<AdminPointsRecordVO> page = new Page<>(1, maxRows, false);
        IPage<AdminPointsRecordVO> result = pointsRecordMapper.selectAdminPage(page, query);
        result.getRecords().forEach(AdminPointsRecordVO::fillChangeTypeText);
        return result.getRecords();
    }

    /** 积分账户多条件检索 */
    public PageResult<AdminPointsAccountVO> pageAccounts(AdminPointsQuery query) {
        Page<AdminPointsAccountVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        return PageUtil.of(pointsAccountMapper.selectAdminPage(page, query));
    }

    /**
     * 管理员调整积分。
     *
     * <p>本方法只做参数校验与幂等键组装，真正的记账逻辑全部交给
     * {@link PointsService#change} —— 积分写入口只有一个，
     * 任何"图省事直接 update 余额"的写法都会破坏账目一致性。
     *
     * @return 调整后的余额
     */
    @Transactional(rollbackFor = Exception.class)
    public int adjust(PointsAdjustDTO dto, Long adminId) {
        if (dto.getPoints() == null || dto.getPoints() == 0) {
            throw new BizException(ResultCode.POINTS_ADJUST_VALUE_INVALID);
        }
        if (!StringUtils.hasText(dto.getRemark())) {
            // 调整原因必填：积分是平台负债，任何一笔变动都要能回答"为什么"
            throw new BizException(ResultCode.POINTS_ADJUST_REASON_REQUIRED);
        }

        String bizNo = StringUtils.hasText(dto.getRequestId())
                ? ADJUST_BIZ_PREFIX + dto.getRequestId()
                : null;

        int balance = pointsService.change(
                dto.getUserId(),
                dto.getPoints(),
                PointsChangeTypeEnum.ADMIN_ADJUST,
                bizNo,
                null,
                dto.getRemark(),
                adminId);

        log.info("管理员调整积分 | adminId={} | userId={} | 调整={} | 余额={} | 幂等键={}",
                adminId, dto.getUserId(), dto.getPoints(), balance,
                bizNo == null ? "未提供（不保证幂等）" : bizNo);
        return balance;
    }
}
