package com.nqx.service.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.RepairApplyMapper;
import com.nqx.pojo.dto.app.AdminRepairQuery;
import com.nqx.pojo.dto.app.RepairHandleDTO;
import com.nqx.pojo.entity.RepairApply;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.AdminRepairApplyVO;
import com.nqx.pojo.vo.app.RepairApplyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 管理端 - 维修工单处理。
 *
 * <h2>为什么现在才有这个服务</h2>
 *
 * <p>维修流程一直是**断头**的：小程序能提交（{@code POST /app/repair/apply}），
 * 用户能看到自己的申请，状态却永远停在「待处理」—— 因为后台**根本没有
 * 维修相关的接口**，没人知道这些申请存在。
 *
 * <p>状态取值（1待处理 / 2处理中 / 3已完成 / 4已驳回）早就定义好了，
 * 后端 VO 和小程序文案也完全一致，缺的只是"能让它往前走"的操作入口。
 *
 * <h2>状态流转</h2>
 *
 * <pre>
 *   1 待处理 ──受理──▶ 2 处理中 ──完成──▶ 3 已完成
 *       │                  │
 *       └──────驳回────────┴──▶ 4 已驳回
 * </pre>
 *
 * <p>没有"从已完成回到处理中"的路径：工单一旦完成，就是履约结束。
 * 真要重开，应该新建一张工单 —— 那样才留得下痕迹。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRepairApplyService {

    /** 状态常量：与 {@code RepairApplyVO.statusText} 的 switch 分支一一对应 */
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_PROCESSING = 2;
    private static final int STATUS_COMPLETED = 3;
    private static final int STATUS_REJECTED = 4;

    private final RepairApplyMapper repairApplyMapper;

    /** 多条件分页检索（含申请人昵称、订单号，SQL 见 RepairApplyMapper.xml） */
    public PageResult<AdminRepairApplyVO> page(AdminRepairQuery query) {
        Page<AdminRepairApplyVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<AdminRepairApplyVO> result = repairApplyMapper.selectAdminPage(page, query);
        /*
         * 必须手动补派生字段。
         *
         * 列表走的是 MyBatis 的按列映射，**不会调用 AdminRepairApplyVO.from()** ——
         * 所以 statusText / warrantyTypeText / images 这三个"算出来的"字段
         * 在列表结果里全是 null。接口照样返回 200，前端写 `row.statusText || '-'`
         * 就安静地显示成「-」，页面上看不出任何异常。
         *
         * 我是通过实际看一眼响应体才发现这件事的：编译通过、页面不报错，
         * 都说明不了字段有没有值。
         */
        result.getRecords().forEach(AdminRepairApplyVO::fillDerivedFields);
        return PageUtil.of(result);
    }

    /**
     * 工单详情。
     *
     * <p>管理端**不校验归属**：管理员本来就要处理所有人的工单。
     * 这一点与小程序端的 {@code detail} 正好相反 —— 那边必须校验
     * （否则任何人都能看别人的故障描述和手机号）。
     */
    public AdminRepairApplyVO detail(Long id) {
        RepairApply apply = repairApplyMapper.selectById(id);
        BizException.assertNotNull(apply, ResultCode.REPAIR_APPLY_NOT_FOUND);
        return AdminRepairApplyVO.from(apply);
    }

    /**
     * 受理：待处理 → 处理中。
     *
     * <p>这个状态的意义是"有人接了"，让提交人能看出自己的申请**被看到了**。
     * 直接一步跳到完成会让用户在整个过程中完全不知道进展。
     */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long id, RepairHandleDTO dto, Long adminId) {
        RepairApply apply = requireApply(id);
        requireStatus(apply, STATUS_PENDING, "受理");
        transit(apply, STATUS_PROCESSING, dto, adminId, "已受理，维修中");
    }

    /**
     * 完成：处理中 → 已完成。
     *
     * <p>只允许从「处理中」完成。从「待处理」直接完成意味着绕过受理，
     * 那样用户在全程看到的都是"待处理"，最后突然变成"已完成" ——
     * 中间没有任何反馈，体验上等于没人管过。
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id, RepairHandleDTO dto, Long adminId) {
        RepairApply apply = requireApply(id);
        requireStatus(apply, STATUS_PROCESSING, "完成");
        transit(apply, STATUS_COMPLETED, dto, adminId, "维修已完成");
    }

    /**
     * 驳回：待处理 / 处理中 → 已驳回。
     *
     * <p><b>驳回原因必填。</b>用户看到"申请被拒绝"而不知道为什么，
     * 只能来问客服 —— 那等于把一次沟通成本转嫁给了用户。
     * 写清原因（不在保修范围、故障不在维修范围、请联系厂家…）能直接省掉这一轮。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, RepairHandleDTO dto, Long adminId) {
        RepairApply apply = requireApply(id);
        Integer status = apply.getStatus();
        if (status != STATUS_PENDING && status != STATUS_PROCESSING) {
            throw new BizException(ResultCode.REPAIR_ALREADY_PROCESSED,
                    String.format("当前状态「%s」不能驳回", RepairApplyVO.statusText(status)));
        }
        if (dto == null || !StringUtils.hasText(dto.getRemark())) {
            throw new BizException(ResultCode.PARAM_ERROR, "请填写驳回原因，用户需要知道为什么");
        }
        transit(apply, STATUS_REJECTED, dto, adminId, "已驳回");
    }

    // ==================== 私有方法 ====================

    private RepairApply requireApply(Long id) {
        RepairApply apply = repairApplyMapper.selectById(id);
        BizException.assertNotNull(apply, ResultCode.REPAIR_APPLY_NOT_FOUND);
        return apply;
    }

    /**
     * 校验当前状态是否允许该动作。
     *
     * <p>错误提示里带上**当前状态的中文**，而不是只回一句"状态不允许" ——
     * 管理员看到"当前状态「已完成」不能受理"能立刻明白发生了什么；
     * 看到"状态不允许"则只能猜。**错误信息应该省掉使用者的猜测。**
     */
    private void requireStatus(RepairApply apply, int expected, String action) {
        if (apply.getStatus() == null || apply.getStatus() != expected) {
            throw new BizException(ResultCode.REPAIR_ALREADY_PROCESSED,
                    String.format("当前状态「%s」不能%s，请刷新后重试",
                            RepairApplyVO.statusText(apply.getStatus()), action));
        }
    }

    private void transit(RepairApply apply, int targetStatus, RepairHandleDTO dto,
                         Long adminId, String logAction) {
        RepairApply update = new RepairApply();
        update.setId(apply.getId());
        update.setStatus(targetStatus);
        update.setHandlerId(adminId);
        update.setHandleTime(LocalDateTime.now());
        // 备注为空时写 null 而不是空串：库里"没有备注"应该只有一种表示，
        // 混着 '' 和 NULL 会让后续的判空逻辑到处补漏
        update.setHandleRemark(dto == null || !StringUtils.hasText(dto.getRemark())
                ? null : dto.getRemark().trim());
        repairApplyMapper.updateById(update);

        log.info("维修工单处理 | applyNo={} | {} -> {} | adminId={} | {}",
                apply.getApplyNo(), RepairApplyVO.statusText(apply.getStatus()),
                RepairApplyVO.statusText(targetStatus), adminId, logAction);
    }
}
