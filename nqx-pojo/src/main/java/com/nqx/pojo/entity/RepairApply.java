package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 维修申请
 */
@Data
@TableName("repair_apply")
public class RepairApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 申请单号 */
    private String applyNo;

    /** 申请人 ID */
    private Long userId;

    /** 关联订单 ID（可空：也可对未下单的商品申请） */
    private Long orderId;

    /** 关联商品 ID */
    private Long productId;

    /** 商品名称快照 */
    private String productName;

    /** 故障描述 */
    private String faultDesc;

    /** 故障图片 URL 数组 */
    private String images;

    /** 联系人 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 寄修地址 */
    private String address;

    /** 状态 1待处理 2处理中 3已完成 4已驳回 */
    private Integer status;

    /**
     * 保修判定：{@code warranty} 保修期内 / {@code paid} 已过保需付费 / {@code null} 未判定。
     *
     * <p><b>为什么这个字段必须落库</b>：它是提交维修时算出来的业务结论
     * （订单完成时间 + 保修月数），本来只放在响应里返回给用户，表里没有。
     *
     * <p>而它恰恰是管理员处理工单时**最需要**的信息：保修期内免费修、
     * 已过保要报价。没有这一列，管理员只能自己翻订单的收货时间再心算一遍 ——
     * 既慢又容易算错（保修月数是可配置的，不一定是 12）。
     *
     * <p>用 VARCHAR 而不是数字：它表达的是业务结论而非状态码，
     * 字符串让库里的值自解释，将来多一种取值也不需要重新定义数字含义。
     */
    private String warrantyType;

    /** 处理说明 */
    private String handleRemark;

    /** 处理人（管理员）ID */
    private Long handlerId;

    /** 处理时间 */
    private LocalDateTime handleTime;

    /** 申请时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除 0未删 1已删 */
    @TableLogic
    private Integer deleted;
}
