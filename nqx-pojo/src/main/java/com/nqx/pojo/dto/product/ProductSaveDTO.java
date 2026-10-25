package com.nqx.pojo.dto.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 商品新增 / 修改入参。
 *
 * <p>用同一个 DTO 承载新增与修改，靠 {@code id} 是否为空区分：
 * 为空是新增，非空是修改。这样前端只需维护一个表单模型。
 */
@Data
public class ProductSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空，修改时必填 */
    private Long id;

    @NotNull(message = "请选择商品分类")
    private Long categoryId;

    @NotBlank(message = "请输入商品名称")
    @Size(max = 100, message = "商品名称不能超过 100 个字符")
    private String name;

    @Size(max = 200, message = "副标题不能超过 200 个字符")
    private String subTitle;

    @Size(max = 100, message = "规格不能超过 100 个字符")
    private String spec;

    @Size(max = 500, message = "封面图地址过长")
    private String coverUrl;

    /** 轮播图，落库时序列化为 JSON 数组字符串 */
    private List<String> images;

    private String description;

    /**
     * 商品类型 1采购商品 2积分兑换商品 3两者皆可。
     *
     * <p><b>这里曾经用 {@code @Pattern(regexp = "^[123]$")} 校验，是个 bug。</b>
     * jakarta 的 {@code @Pattern} 校验器只接受 {@code CharSequence}，
     * 标在 {@code Integer} 字段上时，Hibernate Validator 会在校验阶段抛
     * {@code HV000030: No validator could be found for constraint 'Pattern'
     * validating type 'java.lang.Integer'}，
     * 结果是接口直接 500，而不是返回一个可读的参数错误 ——
     * <b>校验注解用错类型，会把"友好提示"变成"服务器错误"。</b>
     * 数值范围一律用 {@code @Min}/{@code @Max}。
     */
    @NotNull(message = "请选择商品类型")
    @Min(value = 1, message = "商品类型只能是 1采购商品 2积分兑换商品 3两者皆可")
    @Max(value = 3, message = "商品类型只能是 1采购商品 2积分兑换商品 3两者皆可")
    private Integer productType;

    /** 单价（单位：分）。用整数分而非元，避免浮点误差 */
    @NotNull(message = "请输入商品单价")
    @Min(value = 0, message = "商品单价不能为负数")
    private Integer price;

    /** 积分价 */
    @Min(value = 0, message = "积分价不能为负数")
    private Integer pointsPrice;

    @NotBlank(message = "请输入计量单位")
    @Size(max = 20, message = "计量单位不能超过 20 个字符")
    private String unit;

    @NotNull(message = "请输入库存")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 每人限兑数量，0 表示不限 */
    @Min(value = 0, message = "限兑数量不能为负数")
    private Integer exchangeLimit;

    private Integer isHot;

    /** 状态 0下架 1上架 */
    private Integer status;

    private Integer sort;
}
