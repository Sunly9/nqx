package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.system.LogQuery;
import com.nqx.pojo.vo.system.SysLoginLogVO;
import com.nqx.pojo.vo.system.SysOperationLogDetailVO;
import com.nqx.pojo.vo.system.SysOperationLogVO;
import com.nqx.service.system.SysLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 系统管理 / 日志查询接口。
 *
 * <p>全部是只读接口，因此没有 {@code @Log} 注解：
 * 给「查看日志」这个动作本身再记一条操作日志，会让日志表在管理员翻页时
 * 成倍增长（看 10 页就多 10 条"查看日志"的记录），把真正有价值的记录淹没掉。
 * 权限控制已经由 {@code @SaCheckPermission} 保证。
 */
@Tag(name = "管理端-系统管理/日志", description = "操作日志与登录日志的查询")
@RestController
@RequestMapping("/admin/system/log")
@RequiredArgsConstructor
public class AdminSystemLogController {

    private final SysLogService sysLogService;

    @Operation(summary = "操作日志分页",
            description = "支持模块、操作人、状态与日期区间筛选；列表不返回 request_param 等大字段")
    @SaCheckPermission(value = "sys:log:operation", type = "admin")
    @GetMapping("/operation/page")
    public Result<PageResult<SysOperationLogVO>> operationPage(LogQuery query) {
        return Result.success(sysLogService.pageOperations(query));
    }

    @Operation(summary = "操作日志详情", description = "返回完整的请求参数与响应结果，供抽屉展示")
    @SaCheckPermission(value = "sys:log:operation", type = "admin")
    @GetMapping("/operation/{id}")
    public Result<SysOperationLogDetailVO> operationDetail(@PathVariable Long id) {
        return Result.success(sysLogService.operationDetail(id));
    }

    @Operation(summary = "登录日志分页",
            description = "支持账号、状态与日期区间筛选；包含管理端与小程序端的登录记录")
    @SaCheckPermission(value = "sys:log:login", type = "admin")
    @GetMapping("/login/page")
    public Result<PageResult<SysLoginLogVO>> loginPage(LogQuery query) {
        return Result.success(sysLogService.pageLogins(query));
    }
}
