package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表（回收单/采购单）
 */
@Data
@TableName("biz_order")
public class BizOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单号（业务唯一，对外暴露） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 订单类型 1回收单 2采购单 */
    private Integer orderType;

    /** 回收品类 ID（可空；前端主要按重量计价，品类为辅助信息） */
    private Long recycleCategoryId;

    /**
     * 回收方式：{@code send} 自行寄送 / {@code pickup} 上门回收。
     *
     * <p>仅回收单有值。这个字段对应旧版前端的 {@code type} ——
     * 注意它与 {@link #orderType} 语义完全不同：
     * {@code orderType} 说的是"这是回收单还是采购单"，
     * 而本字段说的是"这单回收物怎么交给我们"。
     * 旧版把两者都叫 type，重构时必须拆开，否则无法同时表达。
     */
    private String recycleMethod;

    /** 订单状态（两套状态机，见 OrderStatusEnum） */
    private Integer status;

    /** 总数量 */
    private Integer totalQuantity;

    /** 总金额（分） */
    private Integer totalAmount;

    /** 总积分 */
    private Integer totalPoints;

    /** 用户预估重量（kg），提交回收单时填写 */
    private BigDecimal estimatedWeight;

    /** 实际回收重量（kg），管理员确认回收时录入 */
    private BigDecimal actualWeight;

    /** 本次回收的碳减排量（kg） */
    private BigDecimal carbonSaved;

    /** 快递单号（用户自行寄回时填写） */
    private String courierNo;

    /**
     * 买家最近一次「提醒发货」的时间（为空表示从未提醒过）。
     *
     * <p>注意：这只记录**买家的催促行为**，与发货本身无关。
     * 发货仍然只能由管理员操作（权限码 {@code order:purchase:deliver}）。
     */
    private LocalDateTime remindShipAt;

    /**
     * 买家累计提醒发货次数。
     *
     * <p>后台用它判断"这单被催了很多次"，从而优先处理。
     * 限频规则（24 小时一次）写在业务层，不在这里。
     */
    private Integer remindShipCount;

    /** 支付方式 1积分支付 2模拟支付 3线下结算 */
    private Integer payType;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 收货地址 ID */
    private Long addressId;

    /** 联系人（下单时快照） */
    private String contactName;

    /** 联系电话（下单时快照） */
    private String contactPhone;

    /** 完整地址（下单时快照） */
    private String addressDetail;

    /** 回收预约上门时间 */
    private LocalDateTime expectTime;

    /** 审核管理员 ID */
    private Long auditorId;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 审核备注 */
    private String auditRemark;

    /** 驳回原因 */
    private String rejectReason;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 取消原因 */
    private String cancelReason;

    /** 用户备注 */
    private String userRemark;

    /** 管理员备注 */
    private String adminRemark;

    /** 凭证图片 URL 数组 */
    private String attachmentUrls;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除 0未删 1已删 */
    @TableLogic
    private Integer deleted;
}
