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
 * 商品
 */
@Data
@TableName("product")
public class Product implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 分类 ID */
    private Long categoryId;

    /** 商品名称 */
    private String name;

    /** 副标题/卖点 */
    private String subTitle;

    /** 规格 如 CR2032 3V */
    private String spec;

    /** 封面图 */
    private String coverUrl;

    /** 轮播图 URL 数组 */
    private String images;

    /** 详情（长文本） */
    private String description;

    /** 类型 1采购商品 2积分兑换商品 3两者皆可 */
    private Integer productType;

    /** 单价（单位：分） */
    private Integer price;

    /** 积分价（积分兑换时使用） */
    private Integer pointsPrice;

    /** 单位 */
    private String unit;

    /** 库存（超卖保护的扣减基数） */
    private Integer stock;

    /** 累计销量 */
    private Integer soldCount;

    /** 每人限兑数量，0 表示不限 */
    private Integer exchangeLimit;

    /** 是否热门 0否 1是 */
    private Integer isHot;

    /** 状态 0下架 1上架 */
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
