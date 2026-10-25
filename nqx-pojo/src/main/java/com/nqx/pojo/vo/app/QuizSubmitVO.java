package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 提交答案结果。
 *
 * <p>字段名与旧版前端的解构语句一一对应：
 * <pre>{@code
 * const { isCorrect, pointsAwarded, explanation, correctIndex } = res.data;
 * }</pre>
 * 少任何一个，页面上的"答对了/答错了"与解析展示都会失效。
 */
@Data
public class QuizSubmitVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否答对 */
    @JsonProperty("isCorrect")
    private Boolean isCorrect;

    /** 本次获得积分（答错为 0） */
    @JsonProperty("pointsAwarded")
    private Integer pointsAwarded;

    /** 正确答案的选项下标（单选时有效；多选或无法映射时为 -1） */
    @JsonProperty("correctIndex")
    private Integer correctIndex;

    /** 正确答案文本，如 "A" 或 "AB" */
    @JsonProperty("correctAnswer")
    private String correctAnswer;

    /** 答案解析 */
    private String explanation;

    /** 发放后的积分余额 */
    @JsonProperty("balanceAfter")
    private Integer balanceAfter;

    public static QuizSubmitVO of(boolean correct, int pointsAwarded, int correctIndex,
                                  String correctAnswer, String explanation, Integer balanceAfter) {
        QuizSubmitVO vo = new QuizSubmitVO();
        vo.setIsCorrect(correct);
        vo.setPointsAwarded(pointsAwarded);
        vo.setCorrectIndex(correctIndex);
        vo.setCorrectAnswer(correctAnswer);
        vo.setExplanation(explanation);
        vo.setBalanceAfter(balanceAfter);
        return vo;
    }
}
