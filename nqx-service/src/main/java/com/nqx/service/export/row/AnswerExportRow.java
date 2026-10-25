package com.nqx.service.export.row;

import cn.idev.excel.annotation.ExcelProperty;
import com.nqx.pojo.vo.question.AdminUserAnswerVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.format.DateTimeFormatter;

/**
 * 答题记录导出的一行。
 *
 * <p>与 {@link OrderExportRow} 同样放在 service 模块（原因见那个类的说明）。
 *
 * <h2>为什么把「标准答案」和「用户答案」都导出来</h2>
 *
 * <p>只导"是否正确"的话，运营看到一片「错误」也无法判断是题目配错了
 * 还是用户不会 —— 而这两者的处理方式完全不同（前者改题，后者不用管）。
 * 两个答案并排放，一眼就能看出"是不是标准答案配错了"。
 *
 * <p>注意：**已有人作答的题目不允许修改正确答案**（见 QuizService），
 * 这份导出正是排查"答案配错"时需要的证据。
 */
@Data
public class AnswerExportRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ExcelProperty("记录号")
    private Long id;

    @ExcelProperty("用户昵称")
    private String nickname;

    @ExcelProperty("用户ID")
    private Long userId;

    @ExcelProperty("题目")
    private String questionContent;

    @ExcelProperty("标准答案")
    private String correctAnswer;

    @ExcelProperty("用户答案")
    private String userAnswer;

    @ExcelProperty("是否正确")
    private String correctText;

    @ExcelProperty("获得积分")
    private Integer gainPoints;

    @ExcelProperty("用时(秒)")
    private Integer costTime;

    @ExcelProperty("答题日期")
    private String questionDate;

    @ExcelProperty("提交时间")
    private String createTime;

    public static AnswerExportRow from(AdminUserAnswerVO vo) {
        AnswerExportRow row = new AnswerExportRow();
        row.setId(vo.getId());
        row.setNickname(vo.getNickname());
        row.setUserId(vo.getUserId());
        row.setQuestionContent(vo.getQuestionContent());
        row.setCorrectAnswer(vo.getCorrectAnswer());
        row.setUserAnswer(vo.getUserAnswer());
        // 数据库存的是 0/1，导出成中文，避免拿到表的人还要问"1 是对还是错"
        row.setCorrectText(vo.getIsCorrect() != null && vo.getIsCorrect() == 1 ? "正确" : "错误");
        row.setGainPoints(vo.getGainPoints());
        row.setCostTime(vo.getCostTime());
        row.setQuestionDate(vo.getQuestionDate() == null ? null : vo.getQuestionDate().toString());
        row.setCreateTime(vo.getCreateTime() == null ? null : DTF.format(vo.getCreateTime()));
        return row;
    }
}
