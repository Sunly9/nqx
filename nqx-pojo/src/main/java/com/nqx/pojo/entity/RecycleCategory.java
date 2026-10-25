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
 * 回收品类
 */
@Data
@TableName("recycle_category")
public class RecycleCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 品类名称 如 锂锰纽扣电池 */
    private String name;

    /** 图标 URL */
    private String icon;

    /** 计量单位。统一为 kg —— 回收单提交的是重量而非个数 */
    private String unit;

    /**
     * 每公斤可获积分。
     *
     * <p>V6 迁移前叫 {@code pointsPerUnit}（按个数计价），与前端实际提交的
     * <b>重量</b>不匹配。统一为按重量计价后，本字段与 {@code biz_order.actual_weight}
     * 直接相乘即可算出积分，不再需要任何换算。
     */
    private Integer pointsPerKg;

    /** 回收说明/注意事项 */
    private String description;

    /** 状态 0禁用 1启用 */
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
