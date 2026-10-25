package com.nqx.pojo.dto.order;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 提交采购单入参。
 *
 * <h2>三种下单来源</h2>
 *
 * <ul>
 *   <li><b>{@code products} 数组（旧小程序）</b>——旧版采购下单页把选中的商品
 *       直接放在 {@code products} 里、地址也内联在 {@code address} 对象中：</li>
 * </ul>
 * <pre>{@code
 * { products: [ { id, count, name, price, image } ],
 *   total_amount: 1.00,
 *   address: { name, phone, address } }
 * }</pre>
 * <ul>
 *   <li>{@code source = "cart"}：结算当前用户购物车中已勾选的商品；</li>
 *   <li>{@code source = "direct"}：立即购买，传 {@code productId} 与 {@code quantity}。</li>
 * </ul>
 *
 * <p>三者共用同一个接口而不是拆成三个，因为后续流程（校验地址、扣库存、
 * 写单、投递延迟队列）完全一致，只有"明细从哪来"这一步不同。
 *
 * <h2>⚠️ 两个安全要点</h2>
 *
 * <p><b>① 旧版请求体里的 {@code price} 与 {@code total_amount} 一律忽略。</b>
 * 这个 DTO 里根本没有对应属性 —— 金额必须由服务端按数据库里的单价重新计算。
 * 若采信客户端报价，用户把 {@code price} 从 1.00 改成 0.01 就能一分钱买东西。
 * 旧版的订单结算接口就是让客户端传 {@code actual_weight}，属于同一类漏洞。
 *
 * <p><b>② 内联地址不落 {@code user_address} 表。</b>
 * 它只作为订单快照写入 {@code biz_order.contact_name / contact_phone / address_detail}，
 * 与"从地址簿选一个"的效果相同，但不会让旧客户端往用户地址簿里塞数据。
 */
@Data
public class PurchaseOrderCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 下单来源 cart / direct；传了 products 时忽略本字段 */
    private String source;

    /** 立即购买的商品 ID（source=direct 时必填） */
    @JsonProperty("product_id")
    @JsonAlias({"productId"})
    private Long productId;

    /** 立即购买的数量 */
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;

    /**
     * 收货地址 ID（新版客户端）。
     *
     * <p>与 {@link #address} <b>二选一</b>：传了 addressId 就按地址簿校验归属，
     * 否则用内联地址。两者都缺时才报"请选择收货地址"。
     */
    @JsonProperty("address_id")
    @JsonAlias({"addressId"})
    private Long addressId;

    /** 旧版内联地址（与 addressId 二选一） */
    private InlineAddress address;

    /** 旧版商品数组（与 source/productId 三选一） */
    private List<InlineItem> products;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    public boolean isDirect() {
        return "direct".equalsIgnoreCase(source);
    }

    /** 是否为旧版下单请求（带商品数组） */
    public boolean hasInlineProducts() {
        return products != null && !products.isEmpty();
    }

    /** 是否提供了可用的内联地址（至少要有一段非空地址文本） */
    public boolean hasInlineAddress() {
        return address != null
                && address.getAddress() != null
                && !address.getAddress().isBlank();
    }

    /**
     * 旧版内联地址。
     *
     * <p>字段名与旧页面提交的一致：{@code {name, phone, address}}。
     */
    @Data
    public static class InlineAddress implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String name;

        private String phone;

        /** 完整地址文本 */
        private String address;
    }

    /**
     * 旧版商品项。
     *
     * <p>用 {@code @JsonAlias} 同时接受新旧命名：
     * 旧页面发 {@code id} 与 {@code count}，新客户端可能发
     * {@code product_id} 与 {@code quantity}。
     *
     * <p><b>刻意不接收 {@code price} 与 {@code image}</b> ——
     * 单价必须由服务端从数据库读取，商品名与封面也从订单明细快照取。
     */
    @Data
    public static class InlineItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @JsonProperty("id")
        @JsonAlias({"product_id", "productId"})
        private Long productId;

        @JsonProperty("count")
        @JsonAlias({"quantity", "num"})
        private Integer quantity;
    }
}
