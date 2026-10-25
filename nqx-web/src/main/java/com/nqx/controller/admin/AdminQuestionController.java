package com.nqx.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.nqx.common.annotation.Log;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.question.QuestionQuery;
import com.nqx.pojo.dto.question.QuestionSaveDTO;
import com.nqx.pojo.entity.Question;
import com.nqx.pojo.vo.question.QuestionDetailVO;
import com.nqx.service.quiz.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端题库接口。
 *
 * <p>每个方法的权限码与工具栏按钮一一对应（question:add / edit / delete / changeStatus），
 * 与商品模块的组织方式保持一致 —— 权限粒度对齐"页面上的一个按钮"，
 * 这样分配角色时不需要在脑子里做映射。
 */
@Tag(name = "管理端-题库", description = "每日问答题库的多条件检索、增删改、发布/下线")
@RestController
@RequestMapping("/admin/question")
@RequiredArgsConstructor
public class AdminQuestionController {

    private final QuizService quizService;

    @Operation(summary = "题库多条件检索",
            description = "支持题干关键词、题型、难度、状态、发布日期的组合查询")
    @SaCheckPermission(value = "question:list", type = "admin")
    @GetMapping("/page")
    public Result<PageResult<Question>> page(QuestionQuery query) {
        return Result.success(quizService.adminPage(query));
    }

    @Operation(summary = "题目详情",
            description = "列表接口为控制响应体不返回 options，编辑弹窗靠这个接口取完整选项")
    @SaCheckPermission(value = "question:list", type = "admin")
    @GetMapping("/{id}")
    public Result<QuestionDetailVO> detail(@PathVariable Long id) {
        return Result.success(quizService.adminDetail(id));
    }

    @Operation(summary = "新增题目",
            description = "不传 status 时落草稿（0）。选项必须是 [{key,value}] 形式的 JSON，且正确答案必须落在选项 key 里")
    @Log(module = "问答管理", operation = "新增题目")
    @SaCheckPermission(value = "question:add", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid QuestionSaveDTO dto) {
        return Result.success("新增成功", quizService.createQuestion(dto));
    }

    @Operation(summary = "修改题目",
            description = "状态由发布/下线接口负责，这里不传。已有用户作答的题目不允许修改正确答案")
    @Log(module = "问答管理", operation = "修改题目")
    @SaCheckPermission(value = "question:edit", type = "admin")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid QuestionSaveDTO dto) {
        quizService.updateQuestion(dto);
        return Result.success("修改成功", null);
    }

    @Operation(summary = "删除题目",
            description = "逻辑删除。已有用户作答的题目会被拒绝，请改为下线")
    @Log(module = "问答管理", operation = "删除题目")
    @SaCheckPermission(value = "question:delete", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        quizService.deleteQuestion(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "发布 / 下线题目",
            description = "status：0草稿、1已发布、2已下线。发布前会再校验一次题目内容完整性")
    @Log(module = "问答管理", operation = "题目发布或下线")
    @SaCheckPermission(value = "question:changeStatus", type = "admin")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        quizService.changeStatus(id, status);
        return Result.success(switch (status) {
            case 1 -> "已发布";
            case 2 -> "已下线";
            default -> "已转为草稿";
        }, null);
    }
}
