package com.nqx.pojo.dto.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 提交答题入参。
 *
 * <p>同时支持两种答案表达方式，兼容旧前端与更清晰的写法：
 * <ul>
 *   <li>{@code answer_index}：选项下标（0 基），旧版前端就是用这个；</li>
 *   <li>{@code answer}：答案文本，如 {@code "A"} 或 {@code "AB"}（多选只能用它）。</li>
 * </ul>
 * 两者都传时以 {@code answer} 为准。若只支持下标，多选题就永远无法表达。
 */
@Data
public class QuizSubmitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少题目 ID")
    @JsonProperty("question_id")
    private Long questionId;

    /** 选项下标（0 基） */
    @JsonProperty("answer_index")
    private Integer answerIndex;

    /** 答案文本，如 "A" 或 "AB" */
    private String answer;

    /** 答题耗时（秒），可选 */
    @JsonProperty("cost_time")
    private Integer costTime;

    public boolean hasAnyAnswer() {
        return answerIndex != null || (answer != null && !answer.isBlank());
    }
}
