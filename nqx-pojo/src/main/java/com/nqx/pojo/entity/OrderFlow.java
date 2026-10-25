package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单流转日志
 */
@Data
@TableName("order_flow")
public class OrderFlow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单 ID */
    private Long orderId;

    /** 订单号（冗余） */
    private String orderNo;

    /** 原状态 */
    private Integer fromStatus;

    /** 新状态 */
    private Integer toStatus;

    /** 流转动作 如 SUBMIT/AUDIT_PASS/CANCEL */
    private String action;

    /** 操作人 ID */
    private Long operatorId;

    /** 操作人类型 1用户 2管理员 3系统 */
    private Integer operatorType;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;
}
