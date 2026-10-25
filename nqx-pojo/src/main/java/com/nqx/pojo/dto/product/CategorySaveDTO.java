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
 * 商品分类新增 / 修改入参。
 *
 * <p>与 {@code ProductSaveDTO} 保持同一套约定：新增与修改共用一个 DTO，
 * 靠 {@code id} 是否为空区分（为空是新增，非空是修改），前端只维护一个表单模型。
 * 「修改时 id 必填」这条规则放在 Service 里断言，因为同一个 DTO 的两个入口
 * 校验规则不同，用注解表达不了，硬拆成两个 DTO 又会让前端多维护一份字段。
 */
@Data
public class CategorySaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空，修改时必填 */
    private Long id;

    /**
     * 父级分类 ID，0 表示根分类。
     *
     * <p>这里刻意用 {@code @NotNull} 拦住，而不是在 Service 里把 null 静默兜底成 0：
     * 「没传」和「明确传 0（挂到根）」是两种完全不同的语义。若静默兜底，
     * 前端漏传 parentId 时，一个本该挂在下级的新建分类会被悄悄提到根节点，
     * 运营看到的是「分类位置不对」而不是一条报错，排查成本极高。
     */
    @NotNull(message = "请选择上级分类，根分类请传 0")
    @Min(value = 0, message = "上级分类 ID 不合法")
    private Long parentId;

    @NotBlank(message = "请输入分类名称")
    @Size(max = 50, message = "分类名称不能超过 50 个字符")
    private String name;

    @Size(max = 500, message = "图标地址不能超过 500 个字符")
    private String icon;

    @Min(value = 0, message = "排序值不能为负数")
    private Integer sort;

    /**
     * 状态 0禁用 1启用。
     *
     * <p>用 {@code @Min/@Max} 而不是 {@code @Pattern}：{@code @Pattern} 的校验器
     * 只接受 {@code CharSequence}，标注在 {@code Integer} 字段上会在校验阶段抛
     * {@code UnexpectedTypeException}（HV000030），接口直接 500。
     */
    @Min(value = 0, message = "状态只能是 0（禁用）或 1（启用）")
    @Max(value = 1, message = "状态只能是 0（禁用）或 1（启用）")
    private Integer status;
}
