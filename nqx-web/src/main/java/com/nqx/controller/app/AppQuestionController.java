package com.nqx.controller.app;

import com.nqx.common.api.PageResult;
import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.QuizSubmitDTO;
import com.nqx.pojo.entity.UserAnswer;
import com.nqx.pojo.vo.app.DailyQuizVO;
import com.nqx.pojo.vo.app.QuizSubmitVO;
import com.nqx.service.quiz.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端每日问答接口。
 *
 * <p>需要登录。旧版 {@code GET /api/quiz/daily?user_id=1} 与
 * {@code POST /api/quiz/submit} 都以请求参数里的 {@code user_id} 为准，
 * 改个数字就能替别人答题刷积分 —— 新版的用户身份一律取自会话。
 */
@Tag(name = "小程序端-每日问答", description = "今日题目、提交答案、我的答题记录")
@RestController
@RequestMapping("/app/question")
@RequiredArgsConstructor
public class AppQuestionController {

    private final QuizService quizService;

    @Operation(summary = "今日题目",
            description = "已答过时 hasAnswered=true；题目不含答案与解析，防止被抓包看到答案")
    @GetMapping("/today")
    public Result<DailyQuizVO> today() {
        return Result.success(quizService.today());
    }

    @Operation(summary = "提交答案",
            description = "答对发放积分；每人每天只能答一题，重复提交会被拒绝")
    @PostMapping("/submit")
    public Result<QuizSubmitVO> submit(@RequestBody @Valid QuizSubmitDTO dto) {
        return Result.success(quizService.submit(dto));
    }

    @Operation(summary = "我的答题记录")
    @GetMapping("/my-answers")
    public Result<PageResult<UserAnswer>> myAnswers(
            @RequestParam(value = "pageNum", defaultValue = "1") long pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.success(quizService.myAnswers(pageNum, pageSize));
    }
}
