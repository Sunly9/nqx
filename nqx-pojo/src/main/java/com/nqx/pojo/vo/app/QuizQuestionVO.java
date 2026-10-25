package com.nqx.pojo.vo.app;

import cn.hutool.json.JSONUtil;
import com.nqx.pojo.entity.Question;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 每日问答题（下发给用户）。
 *
 * <h2>刻意不包含答案与解析</h2>
 *
 * <p>这是一个容易被忽视的安全点：如果把 {@code correctAnswer} 与 {@code analysis}
 * 一起下发，用户只要打开开发者工具看一眼网络响应就能看到答案 ——
 * 每日问答也就失去了意义。
 *
 * <p>旧版 Node 是靠 {@code delete question.correct_index; delete question.explanation;}
 * 在返回前删字段来做到的，但那种做法很脆弱：只要有人新增一个字段忘了删，
 * 答案就又漏出去了。这里改用<b>专门的 VO 白名单</b> ——
 * 只有明确写进这个类的字段才会出去，新增实体字段默认不会泄露。
 * <b>白名单比黑名单安全</b>，这是接口设计里的一条通用原则。
 */
@Slf4j
@Data
public class QuizQuestionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 题干。
     *
     * <p>字段名是 {@code question} 而不是 {@code content}，因为旧版前端的
     * 题目对象来自 {@code quiz_questions} 表，列名就叫 {@code question}，
     * 页面模板里直接引用 {@code question.question}。
     */
    private String question;

    /**
     * 选项列表。
     *
     * <p>直接给数组而不是 JSON 字符串 —— 虽然旧前端写了
     * {@code if (typeof question.options === 'string') JSON.parse(...)}
     * 能兼容字符串，但给数组可以省掉这次解析，语义也更清晰。
     */
    private List<String> options;

    /** 答对可得积分 */
    private Integer points;

    /** 难度 1易 2中 3难 */
    private Integer difficulty;

    public static QuizQuestionVO from(Question question) {
        QuizQuestionVO vo = new QuizQuestionVO();
        vo.setId(question.getId());
        vo.setQuestion(question.getContent());
        vo.setOptions(parseOptions(question.getOptions()));
        vo.setPoints(question.getPoints());
        vo.setDifficulty(question.getDifficulty());
        return vo;
    }

    /**
     * 解析选项 JSON。
     *
     * <p>兼容两种存法：纯字符串数组 {@code ["A选项","B选项"]}，
     * 以及带 key 的对象数组 {@code [{"key":"A","value":"..."}]}。
     * 本项目的初始化数据用的是后者（信息更全），但解析失败时降级为空列表
     * 而不是抛异常 —— 一道题的脏数据不该让整个答题页打不开。
     */
    private static List<String> parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            // 先按对象数组解析（项目内实际使用的格式）
            List<OptionItem> items = JSONUtil.toList(optionsJson, OptionItem.class);
            if (items != null && !items.isEmpty() && items.get(0).getValue() != null) {
                return items.stream().map(OptionItem::getValue).toList();
            }
        } catch (Exception ignored) {
            // 落到下面按字符串数组再试
        }
        try {
            return JSONUtil.toList(optionsJson, String.class);
        } catch (Exception e) {
            log.warn("题目选项 JSON 解析失败，已降级为空列表 | json={}", optionsJson);
            return Collections.emptyList();
        }
    }

    /** 选项元素，对应 {@code [{"key":"A","value":"..."}]} 结构 */
    @Data
    public static class OptionItem implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String key;
        private String value;
    }
}
