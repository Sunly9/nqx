package com.nqx.pojo.vo.app;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.RepairApply;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 维修申请（小程序端）。
 *
 * <p>{@code type} 字段是旧前端逻辑依赖的关键：
 * <pre>{@code
 * const type = res.data.type;
 * const msg = type === 'warranty' ? '申请成功，您的设备在保修期内！'
 *                                 : '申请成功，已过保修期，将会有专人联系您报价。';
 * }</pre>
 * 旧后端返回的是 {@code 'warranty'} 或 {@code 'paid'}（保修期内 / 需付费），
 * 这里保持一致 —— 页面只判断是否等于 {@code 'warranty'}，
 * 但沿用同样的取值可以让日志与旧数据对得上。
 */
@Data
public class RepairApplyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 保修判定结果：warranty=保修期内，paid=已过保修期需付费 */
    public static final String TYPE_WARRANTY = "warranty";
    public static final String TYPE_PAID = "paid";

    private Long id;

    @JsonProperty("apply_no")
    private String applyNo;

    @JsonProperty("order_id")
    private Long orderId;

    @JsonProperty("product_id")
    private Long productId;

    @JsonProperty("product_name")
    private String productName;

    /** 故障描述，对应旧字段名 description */
    private String description;

    private List<String> images;

    /** 保修判定：warranty / paid */
    private String type;

    /** 状态 1待处理 2处理中 3已完成 4已驳回 */
    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    @JsonProperty("handle_remark")
    private String handleRemark;

    @JsonProperty("created_at")
    private LocalDateTime createTime;

    /**
     * 由实体构建。
     *
     * @param apply       维修申请
     * @param fallbackType 保修判定的**兜底值**。提交申请的那一刻还没落库，
     *                     调用方把它刚算出的结果传进来；查列表/详情时传 null 即可。
     *
     * <p><b>为什么优先读库里的 {@code warranty_type}</b>：这个判定在提交时算一次就固定了。
     * 如果每次都实时重算，那么"保修月数"配置一改，历史申请的性质会跟着变 ——
     * 一条 2025 年判定为"保修期内"的申请，在 2026 年改配置后会变成"已过保"，
     * 而当时的处理结论已经发生了。<b>业务结论一旦产生就该固化。</b>
     */
    public static RepairApplyVO from(RepairApply apply, String fallbackType) {
        RepairApplyVO vo = new RepairApplyVO();
        vo.setId(apply.getId());
        vo.setApplyNo(apply.getApplyNo());
        vo.setOrderId(apply.getOrderId());
        vo.setProductId(apply.getProductId());
        vo.setProductName(apply.getProductName());
        vo.setDescription(apply.getFaultDesc());
        vo.setImages(parseImages(apply.getImages()));
        vo.setType(apply.getWarrantyType() != null ? apply.getWarrantyType() : fallbackType);
        vo.setStatus(apply.getStatus());
        vo.setStatusText(statusText(apply.getStatus()));
        vo.setHandleRemark(apply.getHandleRemark());
        vo.setCreateTime(apply.getCreateTime());
        return vo;
    }

    /** 状态文案。放在后端而不是前端维护映射表 —— 那份表迟早会和后端的状态定义脱节 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "待处理";
            case 2 -> "处理中";
            case 3 -> "已完成";
            case 4 -> "已驳回";
            default -> "未知";
        };
    }

    private static List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> list = JSONUtil.toList(imagesJson, String.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
