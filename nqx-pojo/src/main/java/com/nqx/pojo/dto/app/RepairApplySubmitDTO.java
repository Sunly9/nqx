package com.nqx.pojo.dto.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 提交维修申请入参。
 *
 * <p>字段名对齐旧页面的提交体：{@code { order_id, description, images }}。
 * 注意旧页面把故障描述叫 {@code description}，而实体字段是 {@code faultDesc} ——
 * 差异在这一层用 {@link JsonProperty} 抹平。
 */
@Data
public class RepairApplySubmitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonProperty("order_id")
    private Long orderId;

    /**
     * 商品 ID。
     *
     * <p>可选：旧版维修页只带了 {@code order_id} 与描述，没有商品 ID。
     * 缺失时由后端从订单明细里取第一个商品补上，
     * 这样既不要求前端改，也能让维修单关联到具体商品。
     */
    @JsonProperty("product_id")
    private Long productId;

    @NotBlank(message = "请描述故障情况")
    @Size(max = 500, message = "故障描述不能超过 500 字")
    @JsonProperty("description")
    private String faultDesc;

    /** 故障图片 URL 列表，最多 3 张 */
    @Size(max = 3, message = "最多上传 3 张图片")
    private List<String> images;

    /** 联系人，可选，默认取用户昵称 */
    @Size(max = 50, message = "联系人不能超过 50 个字符")
    @JsonProperty("contact_name")
    private String contactName;

    @Size(max = 20, message = "联系电话不能超过 20 个字符")
    @JsonProperty("contact_phone")
    private String contactPhone;
}
