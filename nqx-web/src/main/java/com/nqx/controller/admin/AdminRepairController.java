package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.AdminRepairQuery;
import com.nqx.pojo.dto.app.RepairHandleDTO;
import com.nqx.pojo.vo.app.AdminRepairApplyVO;
import com.nqx.service.app.AdminRepairApplyService;
import com.nqx.service.satoken.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 维修工单。
 *
 * <h2>这个模块补的是一条断头流程</h2>
 *
 * <p>在此之前，小程序能提交维修申请、用户能看到自己的申请列表，
 * 但**后台没有任何维修接口** —— 没人知道这些申请存在，
 * 状态永远停在「待处理」。这是整个系统里唯一一条
 * "用户能提交、但没有任何人能处理"的流程。
 *
 * <p>状态流转（<b>没有回退路径</b>）：
 * <pre>
 *   1 待处理 ──受理──▶ 2 处理中 ──完成──▶ 3 已完成
 *       │                  │
 *       └──────驳回────────┴──▶ 4 已驳回
 * </pre>
 *
 * <h2>为什么三个动作是三个独立接口</h2>
 *
 * <p>它们的作用对象相同、入参几乎相同，但**语义完全不同**：
 * 受理是"我接了"、完成是"修好了"、驳回是"这事我不做"。
 * 合成一个 {@code PUT /handle} 让前端传 status，等于把
 * "什么动作能把工单变成什么状态"这个业务规则交给了前端 —— 而那是越权的口子。
 *
 * <p>每个动作都必须显式写 {@code type = "admin"}：注解默认作用于
 * 「默认登录类型」，而管理端用自定义的 {@code admin} 类型（{@code StpAdminLogic}）。
 */
@Tag(name = "管理端-维修工单", description = "维修申请的检索、详情与受理/完成/驳回")
@RestController
@RequestMapping("/admin/repair")
@RequiredArgsConstructor
public class AdminRepairController {

    private final AdminRepairApplyService adminRepairApplyService;

    @Operation(summary = "维修工单分页",
            description = "支持单号/申请人昵称/商品名的模糊搜索，以及状态、保修判定、申请时间区间的组合筛选")
    @SaCheckPermission(value = "repair:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<AdminRepairApplyVO>> page(AdminRepairQuery query) {
        return Result.success(adminRepairApplyService.page(query));
    }

    @Operation(summary = "维修工单详情")
    @SaCheckPermission(value = "repair:list", type = "admin")
    @GetMapping("/{id}")
    public Result<AdminRepairApplyVO> detail(@PathVariable Long id) {
        return Result.success(adminRepairApplyService.detail(id));
    }

    @Operation(summary = "受理工单", description = "待处理 → 处理中。让提交人看出申请已被看到")
    @Log(module = "维修管理", operation = "受理维修工单")
    @SaCheckPermission(value = "repair:handle", type = "admin")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id,
                               @RequestBody(required = false) @Valid RepairHandleDTO dto) {
        adminRepairApplyService.accept(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.success("已受理", null);
    }

    @Operation(summary = "完成工单", description = "处理中 → 已完成。只能从处理中完成，避免绕过受理")
    @Log(module = "维修管理", operation = "完成维修工单")
    @SaCheckPermission(value = "repair:handle", type = "admin")
    @PostMapping("/{id}/complete")
    public Result<Void> complete(@PathVariable Long id,
                                 @RequestBody(required = false) @Valid RepairHandleDTO dto) {
        adminRepairApplyService.complete(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.success("已完成", null);
    }

    @Operation(summary = "驳回工单", description = "待处理/处理中 → 已驳回。驳回原因必填")
    @Log(module = "维修管理", operation = "驳回维修工单")
    @SaCheckPermission(value = "repair:handle", type = "admin")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @RequestBody @Valid RepairHandleDTO dto) {
        adminRepairApplyService.reject(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.success("已驳回", null);
    }
}
