package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日问答题库
 */
@Data
@TableName("question")
public class Question implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 题干 */
    private String content;

    /** 题型 1单选 2多选 3判断 */
    private Integer questionType;

    /** 选项数组 [{"key":"A","value":"..."}] */
    @TableField("`options`")
    private String options;

    /** 正确答案 如 A 或 AB */
    private String correctAnswer;

    /** 答案解析 */
    private String analysis;

    /** 答对可得积分 */
    private Integer points;

    /** 难度 1易 2中 3难 */
    private Integer difficulty;

    /** 发布/生效日期 */
    private LocalDate publishDate;

    /** 状态 0草稿 1已发布 2已下线 */
    private Integer status;

    /** 排序 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除 0未删 1已删 */
    @TableLogic
    private Integer deleted;
}
