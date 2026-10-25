package com.nqx.controller.app;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.RepairApplySubmitDTO;
import com.nqx.pojo.vo.app.RepairApplyVO;
import com.nqx.service.app.RepairApplyService;
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

/**
 * 小程序端维修申请接口。
 *
 * <p>返回体的 {@code type} 字段（{@code warranty} / {@code paid}）是旧前端
 * 展示文案的依据，务必保留。
 */
@Tag(name = "小程序端-维修申请", description = "提交维修申请、查看我的维修记录")
@RestController
@RequestMapping("/app/repair")
@RequiredArgsConstructor
public class AppRepairController {

    private final RepairApplyService repairApplyService;

    @Operation(summary = "提交维修申请",
            description = "按「确认收货时间 + 保修月数」判定保修期，返回 type=warranty/paid")
    @PostMapping("/apply")
    public Result<RepairApplyVO> apply(@RequestBody @Valid RepairApplySubmitDTO dto) {
        return Result.success("申请已提交", repairApplyService.apply(StpAppUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "我的维修申请列表")
    @GetMapping("/list")
    public Result<PageResult<RepairApplyVO>> list(
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.success(repairApplyService.myApplies(
                StpAppUtil.getLoginIdAsLong(), pageNum, pageSize));
    }

    @Operation(summary = "维修申请详情")
    @GetMapping("/{id}")
    public Result<RepairApplyVO> detail(@PathVariable Long id) {
        return Result.success(repairApplyService.detail(StpAppUtil.getLoginIdAsLong(), id));
    }
}
