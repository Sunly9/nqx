package com.nqx.pojo.dto.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 提交回收单入参。
 *
 * <h2>字段名为什么是下划线 / 短名？</h2>
 *
 * <p>因为它<b>必须</b>与旧版前端提交的请求体逐字一致。旧版前端
 * {@code lab/recycle/send} 页面的提交代码是：
 *
 * <pre>{@code
 * data: {
 *   type: 'send',                        // 回收方式，不是订单类型
 *   weight: this.data.weight,            // 预估重量（kg）
 *   contact_name: this.data.contact,
 *   contact_phone: this.data.phone,
 *   address: this.data.address,
 *   courier_no: courierNo
 * }
 * }</pre>
 *
 * <p>若后端改成驼峰 {@code contactName}，Jackson 反序列化后该字段就是 null，
 * 而 {@code @NotBlank} 会报"请输入联系人" —— 用户明明填了却被提示没填。
 * 这类问题在前端和后端各说各话时非常常见，**DTO 的字段名必须以调用方的契约为准**。
 *
 * <p>用 {@link JsonProperty} 而不是把 Java 字段也写成下划线：
 * Java 侧保持驼峰（{@code contactName}），只在 JSON 层面适配，
 * 这样业务代码里不会出现 {@code dto.getContact_name()} 这种别扭的写法。
 */
@Data
public class RecycleOrderCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 回收方式：{@code send} 自行寄送 / {@code pickup} 上门回收。
     * 前端字段名就是 {@code type}，这里沿用，避免改动页面。
     */
    @NotBlank(message = "请选择回收方式")
    private String type;

    /**
     * 用户预估重量（kg）。
     *
     * <p>前端字段名 {@code weight}，落库到 {@code biz_order.estimated_weight}。
     * 最终积分以管理员现场称重的 {@code actual_weight} 为准 ——
     * 用户自报的重量只能作为调度参考，不能直接用来发积分。
     */
    @NotNull(message = "请填写预估重量")
    @DecimalMin(value = "0.01", message = "预估重量必须大于 0")
    private BigDecimal weight;

    /** 回收品类 ID（可选）。决定积分单价，不填则用站点配置的兜底单价 */
    @JsonProperty("recycle_category_id")
    private Long recycleCategoryId;

    @NotBlank(message = "请填写联系人")
    @Size(max = 50, message = "联系人不能超过 50 个字符")
    @JsonProperty("contact_name")
    private String contactName;

    @NotBlank(message = "请填写联系电话")
    @Size(max = 20, message = "联系电话不能超过 20 个字符")
    @JsonProperty("contact_phone")
    private String contactPhone;

    /** 上门地址（自由文本）。回收是上门取件，不必走收货地址簿 */
    @Size(max = 500, message = "地址不能超过 500 个字符")
    private String address;

    /** 快递单号，自行寄送时由前端生成或用户填写 */
    @Size(max = 64, message = "快递单号不能超过 64 个字符")
    @JsonProperty("courier_no")
    private String courierNo;

    /**
     * 预约上门时间。
     *
     * <p>声明为 String 而不是 LocalDateTime：前端传的格式不固定
     * （可能是 {@code 2026-10-20 09:00}、{@code 2026-10-20T09:00:00}
     * 或带时区的 ISO 串）。若直接用 LocalDateTime，Jackson 遇到不认识的格式
     * 会抛反序列化异常，表现为 400 参数错误，但用户看到的只是"提交失败"。
     * 在 Service 层用宽松解析并允许解析失败降级为 null，容错性更好。
     */
    @JsonProperty("pickup_time")
    private String pickupTime;

    /** 用户备注 */
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    /** 凭证图片 URL 列表（电池照片） */
    private List<String> images;
}
