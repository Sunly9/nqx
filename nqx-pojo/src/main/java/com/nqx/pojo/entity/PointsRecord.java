package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分流水
 */
@Data
@TableName("points_record")
public class PointsRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 变动类型 1注册奖励 2每日问答 3回收返积分 4积分兑换 5兑换退回 6管理员调整 */
    private Integer changeType;

    /** 变动值，正数为增，负数为减 */
    private Integer changeValue;

    /** 变动后余额（对账用） */
    private Integer balanceAfter;

    /** 业务单号（幂等键） */
    private String bizNo;

    /** 业务主键 ID */
    private Long bizId;

    /** 备注 */
    private String remark;

    /** 操作人（管理员调整时） */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
