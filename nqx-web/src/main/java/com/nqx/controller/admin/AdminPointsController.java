package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.points.AdminPointsQuery;
import com.nqx.pojo.dto.points.PointsAdjustDTO;
import com.nqx.pojo.vo.order.AdminPointsAccountVO;
import com.nqx.pojo.vo.order.AdminPointsRecordVO;
import com.nqx.service.points.AdminPointsService;
import com.nqx.service.satoken.StpAdminUtil;
import com.nqx.service.export.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端积分接口。
 */
@Tag(name = "管理端-积分", description = "管理员手动调整用户积分")
@RestController
@RequestMapping("/admin/points")
@RequiredArgsConstructor
public class AdminPointsController {

    private final AdminPointsService adminPointsService;
    private final ExportService exportService;

    @Operation(summary = "调整用户积分",
            description = "正数为增加、负数为扣减；建议传 request_id 以保证重复提交只生效一次")
    @Log(module = "积分管理", operation = "调整用户积分")
    @SaCheckPermission(value = "points:account:adjust", type = "admin")
    @PostMapping("/adjust")
    public Result<Integer> adjust(@RequestBody @Valid PointsAdjustDTO dto) {
        int balance = adminPointsService.adjust(dto, StpAdminUtil.getLoginIdAsLong());
        return Result.success("调整成功", balance);
    }

    @Operation(summary = "积分账户列表", description = "支持按用户 ID、昵称、创建日期筛选；按余额倒序")
    @SaCheckPermission(value = "points:account:list", type = "admin")
    @GetMapping("/account/page")
    public Result<PageResult<AdminPointsAccountVO>> accountPage(AdminPointsQuery query) {
        return Result.success(adminPointsService.pageAccounts(query));
    }

    @Operation(summary = "积分流水列表",
            description = "支持按用户、类型、业务单号、日期区间筛选。日期为闭区间（选了截止日会包含当天全天）")
    @SaCheckPermission(value = "points:record:list", type = "admin")
    @GetMapping("/record/page")
    public Result<PageResult<AdminPointsRecordVO>> recordPage(AdminPointsQuery query) {
        return Result.success(adminPointsService.pageRecords(query));
    }

    /**
     * 导出积分流水（Excel）。
     *
     * <p>返回**下载链接**而不是文件流：云托管对返回包有 1000KiB 限制，
     * 直接吐 xlsx 会被截断。筛选条件与 {@code recordPage} 完全一致。
     *
     * <p>「变动积分」列带正负号 —— 对账时必须能区分加分与扣分。
     */
    @Operation(summary = "导出积分流水",
            description = "按当前筛选条件导出 Excel（上限 1 万条），返回可下载的文件地址")
    @Log(module = "积分管理", operation = "导出积分流水")
    @SaCheckPermission(value = "points:record:export", type = "admin")
    @GetMapping("/record/export")
    public Result<String> exportRecords(AdminPointsQuery query) {
        return Result.success("导出成功", exportService.exportPointsRecords(query));
    }
}
