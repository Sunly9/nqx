package com.nqx.pojo.vo.question;

import com.nqx.pojo.entity.Question;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 题库详情（编辑弹窗用）。
 *
 * <p><b>为什么列表接口不直接带上这些字段，而要单独一个详情接口？</b>
 * 后台题库列表为了控制响应体，刻意不查 {@code options}
 * （见 {@code QuestionMapper.selectAdminPage} 的说明）。
 * 但编辑弹窗必须拿到完整的选项才能回填 —— 直接改列表 SQL 去查 options，
 * 等于为了少数"点编辑"的操作，给每次翻页都多传几十 KB。
 * 单独一个按 id 取详情接口，成本只发生在真正编辑时。
 *
 * <p>顺带把 {@code questionTypeText} 之类的展示文案在 Java 侧补齐：
 * 与积分流水 {@code fillChangeTypeText} 同样的理由 ——
 * 文案来自后端的类型定义，就不该在前端再维护一份映射表。
 */
@Data
public class QuestionDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String content;

    /** 题型 1单选 2多选 3判断 */
    private Integer questionType;

    /** 题型中文，前端直接展示 */
    private String questionTypeText;

    /** 选项 JSON 字符串，原样回传供编辑 */
    private String options;

    private String correctAnswer;

    private String analysis;

    private Integer points;

    private Integer difficulty;

    private LocalDate publishDate;

    private Integer status;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static QuestionDetailVO from(Question question) {
        QuestionDetailVO vo = new QuestionDetailVO();
        vo.setId(question.getId());
        vo.setContent(question.getContent());
        vo.setQuestionType(question.getQuestionType());
        vo.setQuestionTypeText(questionTypeText(question.getQuestionType()));
        vo.setOptions(question.getOptions());
        vo.setCorrectAnswer(question.getCorrectAnswer());
        vo.setAnalysis(question.getAnalysis());
        vo.setPoints(question.getPoints());
        vo.setDifficulty(question.getDifficulty());
        vo.setPublishDate(question.getPublishDate());
        vo.setStatus(question.getStatus());
        vo.setSort(question.getSort());
        vo.setCreateTime(question.getCreateTime());
        vo.setUpdateTime(question.getUpdateTime());
        return vo;
    }

    private static String questionTypeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 1 -> "单选题";
            case 2 -> "多选题";
            case 3 -> "判断题";
            default -> "未知";
        };
    }
}
