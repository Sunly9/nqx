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
 * 商品评价
 */
@Data
@TableName("product_review")
public class ProductReview implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单 ID */
    private Long orderId;

    /** 订单号（冗余，便于排查） */
    private String orderNo;

    /** 评价人 ID */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 评分 1~5 星 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 晒图 URL 数组 */
    private String images;

    /** 是否匿名 0否 1是 */
    private Integer isAnonymous;

    /** 商家回复 */
    private String replyContent;

    /** 回复时间 */
    private LocalDateTime replyTime;

    /** 状态 0隐藏 1显示 */
    private Integer status;

    /** 评价时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除 0未删 1已删 */
    @TableLogic
    private Integer deleted;
}
