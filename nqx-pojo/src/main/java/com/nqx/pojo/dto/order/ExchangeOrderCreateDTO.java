package com.nqx.pojo.dto.order;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分兑换入参。
 *
 * <p>与采购单的差异只有"用什么支付"：采购单付钱，兑换付积分。
 * 后续的履约流程（待发货 → 发货 → 收货）完全一致，
 * 因此复用同一张订单表与同一套状态机 ——
 * 这也是当初把回收单/采购单统一建模的收益之一。
 */
@Data
public class ExchangeOrderCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请选择要兑换的商品")
    @JsonProperty("product_id")
    @JsonAlias({"item_id", "itemId", "productId"})
    private Long productId;

    @Min(value = 1, message = "兑换数量至少为 1")
    private Integer quantity;

    /**
     * 收货地址 ID。
     *
     * <p><b>刻意不是必填。</b>旧版积分兑换页没有"选地址"这一步
     * （它只发 {@code {user_id, item_id, points, item_name}}），
     * 若这里强制必填，那个页面会永远 400。
     *
     * <p>不传时由后端取用户的默认地址（没有默认地址就取最近添加的一条）；
     * 一条地址都没有才报"请先添加收货地址"。
     *
     * <p>为什么由后端兜底而不是让前端补传？因为前端要用它就得先发一次
     * 地址列表请求、再判断哪个是默认 —— 多一次往返，而且这段逻辑
     * 在每个需要地址的页面都要重写一遍。<b>能由后端合理推断的，
     * 就不要变成客户端的义务。</b>
     */
    @JsonProperty("address_id")
    @JsonAlias({"addressId"})
    private Long addressId;

    private String remark;

    /*
     * ⚠️ 注意：旧页面还会传 {@code points}（积分价）与 {@code item_name}。
     * 这两个字段**被刻意忽略**，DTO 里根本没有对应的属性 ——
     * 因为积分价必须由服务端从数据库读取。
     *
     * 若采信客户端传来的 points，用户只要把请求体里的 500 改成 1，
     * 就能用 1 个积分换走 500 积分的商品。
     * 这类"客户端报价"是最经典的一类越权漏洞（旧版的订单结算接口
     * 就是让客户端传 actual_weight，同样的错误）。
     */
}
