package com.nqx.controller.app;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.ProductReviewSubmitDTO;
import com.nqx.pojo.entity.ProductReview;
import com.nqx.pojo.vo.app.ProductReviewVO;
import com.nqx.service.app.ProductReviewService;
import com.nqx.service.satoken.StpAppUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序端商品评价接口。
 *
 * <p>单独一个 Controller 而不是塞进 {@code AppProductController}：
 * 商品浏览与评价是两类职责（前者多数只读、后者必须登录且要校验订单），
 * 分开后 {@code AppProductController} 不必为了评价多注入三个依赖。
 */
@Tag(name = "小程序端-商品评价", description = "提交评价、查看商品评价列表与评分概况")
@RestController
@RequestMapping("/app/product")
@RequiredArgsConstructor
public class AppProductReviewController {

    private final ProductReviewService productReviewService;

    @Operation(summary = "提交商品评价",
            description = "整单批量提交；只有已完成的采购订单可评价，且商品必须在该订单中")
    @PostMapping("/review")
    public Result<Integer> submit(@RequestBody @Valid ProductReviewSubmitDTO dto) {
        int count = productReviewService.submit(StpAppUtil.getLoginIdAsLong(), dto);
        return Result.success("评价成功", count);
    }

    @Operation(summary = "商品评价列表", description = "按时间倒序分页；匿名评价的昵称与头像返回空串")
    @GetMapping("/{productId}/reviews")
    public Result<PageResult<ProductReviewVO>> reviews(
            @PathVariable Long productId,
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.success(productReviewService.pageByProduct(productId, pageNum, pageSize));
    }

    @Operation(summary = "商品评分概况", description = "平均分、评价数、好评数")
    @GetMapping("/{productId}/rating")
    public Result<Map<String, Object>> rating(@PathVariable Long productId) {
        return Result.success(productReviewService.ratingStats(productId));
    }

    @Operation(summary = "我在某订单下的评价", description = "用于前端判断哪些商品还能评价")
    @GetMapping("/review/my")
    public Result<List<ProductReview>> myReviews(@RequestParam("orderId") Long orderId) {
        return Result.success(productReviewService.myReviewsOfOrder(
                StpAppUtil.getLoginIdAsLong(), orderId));
    }
}
