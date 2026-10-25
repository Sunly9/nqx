package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单明细（含商品快照）
 */
@Data
@TableName("order_item")
public class OrderItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单 ID */
    private Long orderId;

    /** 订单号（冗余，便于排查） */
    private String orderNo;

    /** 条目类型 1商品 2回收品类 */
    private Integer itemType;

    /** 商品/回收品类 ID */
    private Long itemId;

    /** 名称快照 */
    private String itemName;

    /** 规格快照 */
    private String itemSpec;

    /** 单位快照 */
    private String itemUnit;

    /** 图片快照 */
    private String itemCover;

    /** 单价快照（分） */
    private Integer price;

    /** 单位积分快照 */
    private Integer points;

    /** 数量 */
    private Integer quantity;

    /** 小计金额（分） */
    private Integer subtotalAmount;

    /** 小计积分 */
    private Integer subtotalPoints;

    /** 创建时间 */
    private LocalDateTime createTime;
}
