package com.nqx.pojo.vo.question;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 后台答题记录列表项。
 *
 * <p>字段来自三张表的 JOIN：{@code user_answer} 本体 + {@code app_user} 的昵称
 * + {@code question} 的题干。后台运营看的是"谁、答了哪道题、对不对"，
 * 只给 {@code user_id} / {@code question_id} 等于把两次查询的活推给使用者。
 *
 * <p><b>为什么连 correctAnswer 一起下发？</b>
 * 只显示"用户答了 B、判为错误"时，管理员无法判断是用户答错还是判题逻辑有问题，
 * 必须再去题库翻一次。把标准答案一并给出，一次就能核对判定是否公平 ——
 * 这是后台审计场景的实际需要，不是把答案"泄露"给考生（管理端接口与小程序端完全隔离）。
 */
@Data
public class AdminUserAnswerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 用户昵称，来自 JOIN app_user */
    private String nickname;

    private Long questionId;

    /** 题干，来自 JOIN question */
    private String questionContent;

    /** 该题的正确答案，供管理员核对判定 */
    private String correctAnswer;

    /** 答题归属日期 */
    private LocalDate questionDate;

    /** 用户答案，如 A、ABD */
    private String userAnswer;

    /** 是否正确 0否 1是 */
    private Integer isCorrect;

    /** 实际获得积分 */
    private Integer gainPoints;

    /** 答题耗时（秒） */
    private Integer costTime;

    /** 答题时间 */
    private LocalDateTime createTime;
}
