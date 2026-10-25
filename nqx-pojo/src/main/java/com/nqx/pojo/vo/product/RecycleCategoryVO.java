package com.nqx.pojo.vo.product;

import com.nqx.pojo.entity.RecycleCategory;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回收品类。
 *
 * <p><b>为什么不直接返回 {@code RecycleCategory} 实体？</b>
 * 实体带 {@code deleted} 这类前端完全用不到的字段；更要紧的是，
 * 实体一旦直接对外，日后往表里加内部字段（成本价、内部备注之类）
 * 就会自动暴露给小程序端，而这类泄露往往到上线后才被发现。
 * VO 是数据离开系统的最后一道关口。
 *
 * <p>{@code unit} 与 {@code pointsPerKg} 是回收单计价的核心字段，
 * 小程序端要用它们做「重量 × 每公斤积分」的实时试算，必须返回。
 */
@Data
public class RecycleCategoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 图标 URL */
    private String icon;

    /** 计量单位，统一为 kg */
    private String unit;

    /** 每公斤可获积分 */
    private Integer pointsPerKg;

    /** 回收说明 / 注意事项 */
    private String description;

    /** 状态 0禁用 1启用 */
    private Integer status;

    private Integer sort;

    /** 创建时间，列表页展示用 */
    private LocalDateTime createTime;

    public static RecycleCategoryVO from(RecycleCategory c) {
        RecycleCategoryVO vo = new RecycleCategoryVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setIcon(c.getIcon());
        vo.setUnit(c.getUnit());
        vo.setPointsPerKg(c.getPointsPerKg());
        vo.setDescription(c.getDescription());
        vo.setStatus(c.getStatus());
        vo.setSort(c.getSort());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }
}
