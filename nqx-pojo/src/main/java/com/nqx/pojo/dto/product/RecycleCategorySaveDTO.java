package com.nqx.pojo.dto.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回收品类新增 / 修改入参。
 *
 * <p>新增与修改共用一个 DTO，靠 {@code id} 是否为空区分，规则同 {@code ProductSaveDTO}。
 *
 * <p>{@code pointsPerKg} 与 {@code unit} 决定用户送来的一公斤旧电池能换多少积分，
 * 是回收单计价的核心字段，因此都是必填且给了长度 / 取值约束 ——
 * 单位留空会直接让「重量 × 每公斤积分」的试算在前端崩掉。
 */
@Data
public class RecycleCategorySaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空，修改时必填 */
    private Long id;

    @NotBlank(message = "请输入品类名称")
    @Size(max = 50, message = "品类名称不能超过 50 个字符")
    private String name;

    @Size(max = 500, message = "图标地址不能超过 500 个字符")
    private String icon;

    @NotBlank(message = "请输入计量单位")
    @Size(max = 20, message = "计量单位不能超过 20 个字符")
    private String unit;

    /**
     * 每公斤可获积分。
     *
     * <p>允许为 0（例如只做环保回收、暂不给积分的品类），但不允许为负：
     * 负数会变成「倒扣积分」，属于必须堵死的数据入口。
     */
    @NotNull(message = "请输入每公斤可获积分")
    @Min(value = 0, message = "每公斤可获积分不能为负数")
    private Integer pointsPerKg;

    @Size(max = 500, message = "回收说明不能超过 500 个字符")
    private String description;

    @Min(value = 0, message = "排序值不能为负数")
    private Integer sort;

    /**
     * 状态 0禁用 1启用。
     *
     * <p>用 {@code @Min/@Max} 而非 {@code @Pattern}：{@code @Pattern} 只支持
     * {@code CharSequence}，标在 {@code Integer} 上会在校验阶段抛
     * {@code UnexpectedTypeException}（HV000030），接口直接 500。
     */
    @Min(value = 0, message = "状态只能是 0（禁用）或 1（启用）")
    @Max(value = 1, message = "状态只能是 0（禁用）或 1（启用）")
    private Integer status;
}
