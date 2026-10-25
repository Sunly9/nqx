package com.nqx.service.app;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.AppUserMapper;
import com.nqx.mapper.BizOrderMapper;
import com.nqx.mapper.OrderItemMapper;
import com.nqx.mapper.RepairApplyMapper;
import com.nqx.pojo.dto.app.RepairApplySubmitDTO;
import com.nqx.pojo.entity.AppUser;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.entity.RepairApply;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.RepairApplyVO;
import com.nqx.service.content.ContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 维修申请服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairApplyService {

    /** 申请单号前缀 */
    private static final String APPLY_NO_PREFIX = "RP";

    private static final DateTimeFormatter APPLY_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 单号冲突重试次数 */
    private static final int MAX_RETRY = 3;

    private final RepairApplyMapper repairApplyMapper;
    private final BizOrderMapper bizOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AppUserMapper appUserMapper;
    private final ContentService contentService;

    /**
     * 提交维修申请。
     *
     * <p>返回的 {@code type} 是旧前端逻辑依赖的关键字段：
     * 页面据它决定展示"在保修期内"还是"已过保修期，将有人联系您报价"。
     *
     * <p><b>保修期怎么算？</b>旧版 Node 的规则是「确认收货时间 + 1 年」，
     * 没有收货时间就退回用「下单时间 + 1 年」。这里保持一致，
     * 只把硬编码的 1 年改成可配置（{@code repair_warranty_months}）——
     * 保修期长度是运营策略，不该写死在代码里。
     */
    @Transactional(rollbackFor = Exception.class)
    public RepairApplyVO apply(Long userId, RepairApplySubmitDTO dto) {
        BizOrder order = null;
        if (dto.getOrderId() != null) {
            order = bizOrderMapper.selectById(dto.getOrderId());
            if (order == null) {
                throw new BizException(ResultCode.REPAIR_ORDER_NOT_FOUND);
            }
            if (!order.getUserId().equals(userId)) {
                throw new BizException(ResultCode.ORDER_NOT_BELONG_TO_USER, "无权对他人订单申请维修");
            }
            if (!OrderTypeEnum.PURCHASE.getCode().equals(order.getOrderType())) {
                throw new BizException(ResultCode.ORDER_TYPE_INVALID, "只有采购订单可以申请维修");
            }
        }

        // 商品：前端可选传；没传就从订单明细里取第一个商品
        Long resolvedProductId = dto.getProductId();
        String resolvedProductName = null;
        if (order != null) {
            List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                    .eq(OrderItem::getOrderId, order.getId()));
            if (!items.isEmpty()) {
                // lambda 只能捕获「实际上的最终变量」（effectively final），
                // 而 resolvedProductId 下面还要被赋值，捕获不了。
                // 因此先把"按哪个商品 ID 找名称"定下来放进一个 final 局部变量，
                // 再回填给外层变量。
                final Long lookupProductId = resolvedProductId != null
                        ? resolvedProductId
                        : items.get(0).getItemId();
                final String fallbackName = items.get(0).getItemName();
                resolvedProductName = items.stream()
                        .filter(i -> i.getItemId().equals(lookupProductId))
                        .map(OrderItem::getItemName)
                        .findFirst()
                        .orElse(fallbackName);
                resolvedProductId = lookupProductId;
            }
        }

        String warrantyType = determineWarrantyType(order);

        // 联系人信息：旧版维修页不收集，因此这里从用户资料兜底。
        // 让用户重复填写自己已经有的昵称和手机号是糟糕的设计；
        // 真的没值时（用户未绑手机）也允许为空，由客服在处理环节补录 ——
        // 不该因为"某一列没值"就把整笔申请挡在门外。
        AppUser applicant = appUserMapper.selectById(userId);

        RepairApply apply = new RepairApply();
        apply.setUserId(userId);
        apply.setOrderId(dto.getOrderId());
        apply.setProductId(resolvedProductId);
        apply.setProductName(resolvedProductName);
        apply.setFaultDesc(dto.getFaultDesc());
        apply.setImages(dto.getImages() == null || dto.getImages().isEmpty()
                ? null : JSONUtil.toJsonStr(dto.getImages()));
        apply.setContactName(StringUtils.hasText(dto.getContactName())
                ? dto.getContactName()
                : (applicant == null ? null : applicant.getNickname()));
        apply.setContactPhone(StringUtils.hasText(dto.getContactPhone())
                ? dto.getContactPhone()
                : (applicant == null ? null : applicant.getPhone()));
        // 状态 1=待处理
        apply.setStatus(1);
        // 保修判定必须落库：管理员处理工单时要靠它决定"免费修还是先报价"。
        // 原来这个结果只放在响应里返回给用户，表里没有 —— 于是拿着工单的人
        // 反而看不到最关键的一条信息，只能自己翻订单的收货时间再心算一遍。
        apply.setWarrantyType(warrantyType);
        apply.setDeleted(CommonConstants.DELETED_NO);
        insertWithUniqueApplyNo(apply);

        log.info("维修申请已提交 | applyNo={} | userId={} | 保修判定={}",
                apply.getApplyNo(), userId, warrantyType);

        return RepairApplyVO.from(apply, warrantyType);
    }

    /** 我的维修申请列表 */
    public PageResult<RepairApplyVO> myApplies(Long userId, long pageNum, long pageSize) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairApply> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(pageNum, 1),
                        Math.min(Math.max(pageSize, 1), CommonConstants.PAGE_SIZE_MAX));

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RepairApply> result =
                repairApplyMapper.selectPage(page, Wrappers.<RepairApply>lambdaQuery()
                        .eq(RepairApply::getUserId, userId)
                        .orderByDesc(RepairApply::getCreateTime)
                        .orderByDesc(RepairApply::getId));

        return PageUtil.of(result, apply -> RepairApplyVO.from(apply,
                determineWarrantyType(bizOrderMapper.selectById(apply.getOrderId()))));
    }

    /** 维修申请详情（校验归属） */
    public RepairApplyVO detail(Long userId, Long id) {
        RepairApply apply = repairApplyMapper.selectById(id);
        BizException.assertNotNull(apply, ResultCode.REPAIR_APPLY_NOT_FOUND);
        if (!apply.getUserId().equals(userId)) {
            // 与订单一样，维修申请也涉及用户隐私（联系方式、故障描述），必须校验归属
            throw new BizException(ResultCode.ORDER_NOT_BELONG_TO_USER, "无权查看他人的维修申请");
        }
        return RepairApplyVO.from(apply,
                determineWarrantyType(bizOrderMapper.selectById(apply.getOrderId())));
    }

    // ==================== 私有方法 ====================

    /**
     * 判定是否在保修期内。
     *
     * <p>基准时间优先取确认收货时间（{@code finishTime}），
     * 缺失时退回下单时间。这与旧版 Node 的判定顺序一致：
     * 保修期应当从"用户拿到货"开始算，而不是从"下单"开始 ——
     * 但历史数据可能没有收货时间，所以需要兜底。
     *
     * <p>订单缺失（用户对未下单的商品申请维修）时一律判为付费维修：
     * 没有购买凭证就无从谈保修，这是唯一安全的选择。
     */
    private String determineWarrantyType(BizOrder order) {
        if (order == null) {
            return RepairApplyVO.TYPE_PAID;
        }
        LocalDateTime base = order.getFinishTime() != null ? order.getFinishTime() : order.getCreateTime();
        if (base == null) {
            return RepairApplyVO.TYPE_PAID;
        }
        int months = contentService.getIntSetting("repair_warranty_months", 12);
        boolean inWarranty = LocalDateTime.now().isBefore(base.plusMonths(months));
        return inWarranty ? RepairApplyVO.TYPE_WARRANTY : RepairApplyVO.TYPE_PAID;
    }

    /**
     * 生成申请单号并插入。
     *
     * <p>与订单号一样采用"生成 → 插入 → 撞唯一键就重试"的模式。
     * 不要先 {@code SELECT} 判断单号是否已存在再插入 ——
     * 那是典型的"检查后使用"竞态，两个并发请求会同时查到"不存在"。
     */
    private void insertWithUniqueApplyNo(RepairApply apply) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            apply.setApplyNo(generateApplyNo());
            try {
                repairApplyMapper.insert(apply);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.warn("维修申请单号冲突，重试第 {} 次 | applyNo={}", attempt, apply.getApplyNo());
            }
        }
        throw new BizException(ResultCode.SYSTEM_ERROR, "申请单号生成失败，请重试");
    }

    private String generateApplyNo() {
        return APPLY_NO_PREFIX + LocalDateTime.now().format(APPLY_NO_TIME)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
