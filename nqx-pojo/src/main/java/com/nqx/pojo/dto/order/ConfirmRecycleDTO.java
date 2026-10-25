package com.nqx.pojo.dto.order;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 管理员确认回收入参。
 */
@Data
public class ConfirmRecycleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 现场称重的实际重量（kg）。
     *
     * <p><b>必须以这里传入的值为准，绝不能用用户提交时自报的 {@code estimatedWeight}。</b>
     * 用户自报 5kg、实际只有 0.5kg 的情况很常见（估重偏差、或故意虚报套积分），
     * 积分按自报重量发放等于把定价权交给了用户。
     */
    @NotNull(message = "请填写实际重量")
    @DecimalMin(value = "0.01", message = "实际重量必须大于 0")
    @JsonProperty("actual_weight")
    @JsonAlias("actualWeight")
    private BigDecimal actualWeight;

    /**
     * 是否同时结算发放积分。
     *
     * <p>默认 {@code true}（现场称重后通常直接结算，减少一次操作）。
     * 若现场还需要复检或走审批，可传 {@code false} 只把订单推进到「待结算」，
     * 之后再单独调用结算接口。
     *
     * <p>用包装类型 {@code Boolean} 而非 {@code boolean}：
     * 基本类型在 JSON 里缺省时会被反序列化成 {@code false}，
     * 于是"不传"会被误解为"不结算"，与默认结算的预期相反。
     */
    private Boolean settle;

    /**
     * 管理员备注。
     *
     * <p>{@code @JsonAlias} 让接口<b>同时接受驼峰与下划线两种写法</b>。
     *
     * <p>为什么需要这个？本项目的 DTO 存在两种命名风格：
     * 小程序端的 DTO 为了兼容旧前端必须用下划线（{@code actual_weight}），
     * 管理端的 DTO 是全新的、本可以用驼峰。而两类 DTO 混在一起时，
     * 调用方（包括写前端的人、写测试的人、面试时看代码的人）很容易写错。
     *
     * <p>{@code @JsonAlias} 是"宽容读取"（tolerant reader）思路：
     * <b>输出保持一个明确的规范，输入尽量宽容</b>。
     * 这样即便调用方写成了 {@code actualWeight} 也能正常工作，
     * 不会因为一个大小写/分隔符问题浪费半小时排查。
     */
    @JsonProperty("audit_remark")
    @JsonAlias({"auditRemark", "remark"})
    private String remark;
}
