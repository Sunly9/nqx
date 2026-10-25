package com.nqx.controller.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.common.constant.CommonConstants;
import com.nqx.mapper.PointsAccountMapper;
import com.nqx.pojo.dto.product.ProductQuery;
import com.nqx.pojo.entity.PointsAccount;
import com.nqx.pojo.vo.app.PointsAccountVO;
import com.nqx.pojo.vo.app.PointsRecordVO;
import com.nqx.pojo.vo.app.ProductAppVO;
import com.nqx.pojo.vo.product.ProductVO;
import com.nqx.service.points.PointsService;
import com.nqx.service.product.ProductService;
import com.nqx.service.satoken.StpAppUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端积分接口。
 *
 * <p>用户身份取自 Sa-Token 会话，不接受 {@code user_id} 参数 ——
 * 旧版 {@code GET /api/points/history?user_id=1} 改个数字就能看别人的积分流水。
 */
@Tag(name = "小程序端-积分", description = "积分账户、流水、积分商品")
@RestController
@RequestMapping("/app/points")
@RequiredArgsConstructor
public class AppPointsController {

    /** 积分商品的类型：2 仅积分兑换 3 采购与兑换皆可 */
    private static final List<Integer> EXCHANGE_PRODUCT_TYPES = List.of(2, 3);

    private final PointsService pointsService;
    private final PointsAccountMapper pointsAccountMapper;
    private final ProductService productService;

    @Operation(summary = "我的积分账户", description = "返回可用余额、累计获得、累计消耗")
    @GetMapping("/account")
    public Result<PointsAccountVO> account() {
        Long userId = StpAppUtil.getLoginIdAsLong();
        PointsAccount account = pointsAccountMapper.selectOne(
                Wrappers.<PointsAccount>lambdaQuery().eq(PointsAccount::getUserId, userId));
        if (account == null) {
            // 账户理论上在注册时已创建，缺失时返回 0 而不是报错 ——
            // 让页面能正常打开并显示"0 积分"，比抛异常体验好得多
            return Result.success(PointsAccountVO.of(0, 0, 0));
        }
        return Result.success(PointsAccountVO.of(
                account.getBalance(), account.getTotalEarn(), account.getTotalSpend()));
    }

    @Operation(summary = "我的积分流水", description = "按时间倒序分页；字段同时提供新旧两套命名以兼容旧页面")
    @GetMapping("/record/page")
    public Result<PageResult<PointsRecordVO>> records(
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.success(pointsService.pageRecords(StpAppUtil.getLoginIdAsLong(), pageNum, pageSize));
    }

    @Operation(summary = "积分商品列表",
            description = "只返回支持积分兑换（productType 为 2 或 3）的上架商品；字段名与金额单位对齐旧页面")
    @GetMapping("/goods")
    public Result<PageResult<ProductAppVO>> goods(
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        ProductQuery query = new ProductQuery();
        query.setProductTypes(EXCHANGE_PRODUCT_TYPES);
        // 与商品浏览接口一样，服务端强制只返回上架商品，不信任前端传参
        query.setStatus(CommonConstants.STATUS_ENABLED);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        // 积分商城页读的是 item.points / item.sold / item.image，走小程序端 VO
        return Result.success(productService.page(query).map(ProductAppVO::from));
    }
}
