package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 今日问答题返回。
 *
 * <p>结构与旧版前端的读取方式严格对齐：
 * <pre>{@code
 * if (res.data.hasAnswered) { ... }
 * const question = res.data.data;
 * }</pre>
 * 因此 {@code hasAnswered} 在顶层、题目放在 {@code data} 里。
 */
@Data
public class DailyQuizVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 今天是否已经答过 */
    @JsonProperty("hasAnswered")
    private Boolean hasAnswered;

    /** 已答过时的提示文案 */
    private String message;

    /** 今日题目；已答过时为 null */
    private QuizQuestionVO data;

    public static DailyQuizVO answered(String message) {
        DailyQuizVO vo = new DailyQuizVO();
        vo.setHasAnswered(Boolean.TRUE);
        vo.setMessage(message);
        return vo;
    }

    public static DailyQuizVO of(QuizQuestionVO question) {
        DailyQuizVO vo = new DailyQuizVO();
        vo.setHasAnswered(Boolean.FALSE);
        vo.setData(question);
        return vo;
    }
}
