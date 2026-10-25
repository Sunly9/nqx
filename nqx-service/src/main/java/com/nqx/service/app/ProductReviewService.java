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
import com.nqx.mapper.ProductReviewMapper;
import com.nqx.pojo.dto.app.ProductReviewSubmitDTO;
import com.nqx.pojo.entity.AppUser;
import com.nqx.pojo.entity.BizOrder;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.entity.ProductReview;
import com.nqx.pojo.enums.OrderStatusEnum;
import com.nqx.pojo.enums.OrderTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.ProductReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品评价服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReviewService {

    /** 评价状态：显示中 */
    private static final int STATUS_VISIBLE = 1;

    /** 订单明细类型：商品 */
    private static final int ITEM_TYPE_PRODUCT = 1;

    private final ProductReviewMapper productReviewMapper;
    private final BizOrderMapper bizOrderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AppUserMapper appUserMapper;

    /**
     * 提交评价（整单批量）。
     *
     * <p>三道校验：
     * <ol>
     *   <li><b>订单归属</b>：只能评价自己的订单（防水平越权）；</li>
     *   <li><b>订单状态</b>：只有已完成的采购单才能评价 ——
     *       没收到货就能评价的话，评论区会变成刷好评的入口；</li>
     *   <li><b>商品确实在该订单里</b>：否则用户可以拿一个便宜的订单
     *       去给任意商品刷五星，这是最容易被忽略的一条。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public int submit(Long userId, ProductReviewSubmitDTO dto) {
        BizOrder order = bizOrderMapper.selectById(dto.getOrderId());
        BizException.assertNotNull(order, ResultCode.ORDER_NOT_FOUND);

        if (!order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.ORDER_NOT_BELONG_TO_USER, "无权评价他人的订单");
        }
        if (!OrderTypeEnum.PURCHASE.getCode().equals(order.getOrderType())) {
            throw new BizException(ResultCode.ORDER_TYPE_INVALID, "只有采购订单可以评价商品");
        }
        OrderStatusEnum status = OrderStatusEnum.of(order.getStatus());
        if (status != OrderStatusEnum.PURCHASE_FINISHED) {
            throw new BizException(ResultCode.ORDER_STATUS_ILLEGAL,
                    "订单完成后才能评价，当前状态为「" + (status == null ? "未知" : status.getDesc()) + "」");
        }

        // 该订单里的商品集合，用于校验
        Set<Long> orderProductIds = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderId, order.getId()))
                .stream()
                .filter(item -> item.getItemType() != null && item.getItemType() == ITEM_TYPE_PRODUCT)
                .map(OrderItem::getItemId)
                .collect(Collectors.toSet());

        for (ProductReviewSubmitDTO.ReviewItem item : dto.getReviews()) {
            if (!orderProductIds.contains(item.getProductId())) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "商品不在该订单中，无法评价（商品 ID " + item.getProductId() + "）");
            }
        }

        int inserted = 0;
        for (ProductReviewSubmitDTO.ReviewItem item : dto.getReviews()) {
            ProductReview review = new ProductReview();
            review.setOrderId(order.getId());
            review.setOrderNo(order.getOrderNo());
            review.setUserId(userId);
            review.setProductId(item.getProductId());
            review.setRating(item.getRating());
            review.setContent(item.getContent());
            review.setImages(item.getImages() == null || item.getImages().isEmpty()
                    ? null : JSONUtil.toJsonStr(item.getImages()));
            review.setIsAnonymous(0);
            review.setStatus(STATUS_VISIBLE);
            try {
                productReviewMapper.insert(review);
                inserted++;
            } catch (DuplicateKeyException e) {
                // 该订单的该商品已经评价过。整单事务回滚，
                // 提示用户而不是静默跳过 —— 静默跳过会让用户以为评价成功了
                log.info("重复评价 | orderId={} | productId={} | userId={}",
                        order.getId(), item.getProductId(), userId);
                throw new BizException(ResultCode.REVIEW_ALREADY_EXISTS,
                        "该订单中已有商品被评价过，请勿重复提交");
            }
        }

        log.info("商品评价提交成功 | orderNo={} | userId={} | 条数={}", order.getOrderNo(), userId, inserted);
        return inserted;
    }

    /**
     * 某商品的评价列表（分页）。
     *
     * <p>先查评价、再用 {@code selectBatchIds} 一次性查出涉及的作者，
     * 而不是在循环里逐个查用户（N+1）。评价列表一页 10 条、
     * 可能涉及 10 个不同用户，循环查库就是 10 次往返。
     */
    public PageResult<ProductReviewVO> pageByProduct(Long productId, long pageNum, long pageSize) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductReview> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(pageNum, 1),
                        Math.min(Math.max(pageSize, 1), CommonConstants.PAGE_SIZE_MAX));

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductReview> result =
                productReviewMapper.selectPage(page, Wrappers.<ProductReview>lambdaQuery()
                        .eq(ProductReview::getProductId, productId)
                        .eq(ProductReview::getStatus, STATUS_VISIBLE)
                        .orderByDesc(ProductReview::getCreateTime)
                        .orderByDesc(ProductReview::getId));

        Map<Long, AppUser> userMap = loadUsers(result.getRecords());
        return PageUtil.of(result, review -> {
            AppUser author = userMap.get(review.getUserId());
            return ProductReviewVO.from(review,
                    author == null ? "" : author.getNickname(),
                    author == null ? "" : author.getAvatarUrl());
        });
    }

    /** 商品评分概况 */
    public Map<String, Object> ratingStats(Long productId) {
        Map<String, Object> stats = productReviewMapper.selectRatingStats(productId);
        return stats == null ? new HashMap<>() : stats;
    }

    /** 当前用户对某订单的评价情况，用于前端判断"哪些商品还能评" */
    public List<ProductReview> myReviewsOfOrder(Long userId, Long orderId) {
        BizOrder order = bizOrderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Collections.emptyList();
        }
        return productReviewMapper.selectList(Wrappers.<ProductReview>lambdaQuery()
                .eq(ProductReview::getOrderId, orderId)
                .eq(ProductReview::getUserId, userId));
    }

    private Map<Long, AppUser> loadUsers(List<ProductReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> userIds = reviews.stream().map(ProductReview::getUserId).collect(Collectors.toSet());
        return appUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u, (a, b) -> a, HashMap::new));
    }
}
