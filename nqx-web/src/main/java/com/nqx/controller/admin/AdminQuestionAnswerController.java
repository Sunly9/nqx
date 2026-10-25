package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.question.AdminAnswerQuery;
import com.nqx.pojo.vo.question.AdminUserAnswerVO;
import com.nqx.service.export.ExportService;
import com.nqx.service.quiz.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端答题记录接口。
 *
 * <p><b>为什么单独一个 Controller 而不是塞进 {@code AdminQuestionController}？</b>
 * 两者的权限码分属不同菜单（题库管理 question:list / 答题记录 question:answer:list），
 * 且答题记录是只读的审计视图、题库是写操作。按"一个菜单一个 Controller"划分，
 * 权限配置与接口路径的对应关系一眼可见；混在一起后，读接口和写接口
 * 共享同一个 {@code @RequestMapping} 前缀，很容易在加权限时漏改其中一个。
 */
@Tag(name = "管理端-答题记录", description = "每日问答答题记录的多条件检索")
@RestController
@RequestMapping("/admin/question/answer")
@RequiredArgsConstructor
public class AdminQuestionAnswerController {

    private final QuizService quizService;
    private final ExportService exportService;

    @Operation(summary = "答题记录分页检索",
            description = "支持用户昵称、用户 ID、题目 ID、是否正确、答题日期区间的组合查询。"
                    + "返回含用户昵称与题干（LEFT JOIN 取得，用户或题目被删时这两列为空但记录不会消失）")
    @SaCheckPermission(value = "question:answer:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<AdminUserAnswerVO>> page(AdminAnswerQuery query) {
        return Result.success(quizService.adminAnswerPage(query));
    }

    /**
     * 导出答题记录（Excel）。
     *
     * <p>同时导出「标准答案」与「用户答案」：只看"是否正确"无法判断
     * 是题目配错了还是用户不会，而这两者的处理方式完全不同。
     *
     * <p>返回**下载链接**而不是文件流：云托管对返回包有 1000KiB 限制。
     */
    @Operation(summary = "导出答题记录",
            description = "按当前筛选条件导出 Excel（上限 1 万条），返回可下载的文件地址")
    @Log(module = "问答管理", operation = "导出答题记录")
    @SaCheckPermission(value = "question:answer:export", type = "admin")
    @GetMapping("/export")
    public Result<String> export(AdminAnswerQuery query) {
        return Result.success("导出成功", exportService.exportAnswers(query));
    }
}
