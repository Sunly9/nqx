package com.nqx.pojo.dto.question;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 题库新增/修改入参。
 *
 * <p><b>为什么 options 用 String 而不是 {@code List<Option>}？</b>
 * 实体里的 {@code options} 就是 JSON 字符串列，若 DTO 用对象结构，
 * 就必须在 Service 里再序列化回字符串 —— 多一次转换就多一处可能失败的地方
 * （例如 fastjson/jackson 对中文的转义策略不同，会让同一道题的 options
 * 在"新增"和"初始化脚本写入"两条路径下文本形态不一致）。
 *
 * <p>但这不代表可以放弃校验：JSON 是否合法、每个选项是否有 key/value、
 * correctAnswer 是否真的落在这些 key 里，都在 {@code QuizService} 里逐条校验。
 * 存字符串只是省掉一次无意义的序列化，不是省掉校验。
 *
 * <p>{@code id} 为空即新增，与商品接口保持一致（前端一个表单承载新增与编辑）。
 */
@Data
public class QuestionSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空 */
    private Long id;

    /** 题干 */
    @NotBlank(message = "题干不能为空")
    @Size(max = 500, message = "题干不能超过 500 个字符")
    private String content;

    /** 题型 1单选 2多选 3判断 */
    private Integer questionType;

    /**
     * 选项 JSON 字符串，形如 {@code [{"key":"A","value":"有害垃圾"}]}。
     *
     * <p>不在注解上做格式校验：JSON 的合法性用 {@code ObjectMapper} 判断才准确，
     * 正则匹配 JSON 是出了名的写不对，这里只保证非空。
     */
    @NotBlank(message = "请至少填写两个选项")
    private String options;

    /** 正确答案，选项 key 的组合，如 A、ABD */
    @NotBlank(message = "请选择正确答案")
    @Size(max = 26, message = "正确答案不能超过 26 个选项")
    private String correctAnswer;

    /** 答案解析 */
    @Size(max = 1000, message = "答案解析不能超过 1000 个字符")
    private String analysis;

    /**
     * 答对可得积分。
     *
     * <p>上界 100 不是拍脑袋定的：每日问答是纯发放型业务，
     * 一道题 1000 分意味着一次配置失误就能把全站积分体系冲垮，
     * 而且这种错误只会在"用户突然都来兑换"时才被发现。
     * 真要发大额积分，应当通过管理员调整（有原因、有流水、可追溯）。
     */
    @Min(value = 1, message = "答对得分必须大于 0")
    @Max(value = 100, message = "答对得分不能超过 100")
    private Integer points;

    /** 难度 1易 2中 3难 */
    private Integer difficulty;

    /** 发布/生效日期 */
    private LocalDate publishDate;

    /** 状态 0草稿 1已发布 2已下线 */
    private Integer status;

    /** 排序 */
    private Integer sort;
}
