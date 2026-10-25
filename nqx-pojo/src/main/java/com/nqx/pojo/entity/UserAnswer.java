package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户答题记录
 */
@Data
@TableName("user_answer")
public class UserAnswer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 题目 ID */
    private Long questionId;

    /** 答题归属日期（用于每日统计/排行） */
    private LocalDate questionDate;

    /** 用户答案 */
    @TableField("`user_answer`")
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
