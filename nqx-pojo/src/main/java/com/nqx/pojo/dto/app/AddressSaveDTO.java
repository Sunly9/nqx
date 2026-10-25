package com.nqx.pojo.dto.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.nqx.pojo.jackson.FlexibleZeroOneDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收货地址入参。
 *
 * <p>字段名对齐旧版前端提交的请求体：
 * {@code {name, phone, province, city, district, detail, is_default}}。
 * 旧版 {@code addresses} 表的列名就是 name / phone / detail，
 * 前端直接用了这些名字；新表改成了 receiver_name / receiver_phone / detail_address
 * 更明确的命名，差异由 {@link JsonProperty} 在这一层抹平。
 *
 * <p>为什么新表要用更长的名字？因为在一个同时有"用户"和"收货人"的系统里，
 * 裸的 {@code name} 实在太含糊 —— 看到 {@code addresses.name} 时，
 * 得先确认这是"地址标签名"还是"收货人姓名"。接收方（前端）的命名不必改，
 * 但存储层的命名应当自解释。
 */
@Data
public class AddressSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 修改时必填 */
    private Long id;

    /** 收货人姓名 */
    @NotBlank(message = "请填写收货人")
    @Size(max = 50, message = "收货人不能超过 50 个字符")
    @JsonProperty("name")
    private String receiverName;

    @NotBlank(message = "请填写联系电话")
    @Size(max = 20, message = "联系电话不能超过 20 个字符")
    @JsonProperty("phone")
    private String receiverPhone;

    @Size(max = 50, message = "省份名称过长")
    private String province;

    @Size(max = 50, message = "城市名称过长")
    private String city;

    @Size(max = 50, message = "区县名称过长")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "请填写详细地址")
    @Size(max = 255, message = "详细地址不能超过 255 个字符")
    @JsonProperty("detail")
    private String detailAddress;

    @Size(max = 20, message = "标签不能超过 20 个字符")
    private String tag;

    /**
     * 是否设为默认 0否 1是。
     *
     * <p><b>必须用宽容反序列化器。</b>旧小程序的地址编辑页提交的是
     * <b>JSON 布尔值</b>（{@code is_default: false}），而数据库与新后台用 0/1。
     * 声明成 {@code Integer} 时 Jackson 默认无法接受布尔值，会抛
     * {@code MismatchedInputException} —— 而前端拿到的只是一句"网络错误"，
     * 排查方向很容易跑偏。这个坑真实踩过。
     *
     * <p>详见 {@link FlexibleZeroOneDeserializer}。
     */
    @JsonProperty("is_default")
    @JsonDeserialize(using = FlexibleZeroOneDeserializer.class)
    private Integer isDefault;
}
