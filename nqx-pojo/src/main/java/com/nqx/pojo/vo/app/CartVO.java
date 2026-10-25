package com.nqx.pojo.vo.app;

import com.nqx.pojo.entity.Cart;
import com.nqx.pojo.entity.Product;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 购物车条目（含商品信息）。
 *
 * <p>购物车列表若只返回 {@code product_id}，前端还得为每一项再查一次商品详情（N+1 请求）。
 * 直接带上商品名称、封面、单价、库存，一次请求就能渲染整个购物车页面 ——
 * 而且库存是下单时校验的依据，提前展示能避免用户勾选了缺货商品才发现。
 */
@Data
public class CartVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 购物车条目 ID */
    private Long id;

    private Long productId;

    private String productName;

    private String productSpec;

    private String coverUrl;

    /** 单价（分） */
    private Integer price;

    /** 积分价 */
    private Integer pointsPrice;

    private Integer quantity;

    /** 当前库存，为 0 时前端应提示"已售罄" */
    private Integer stock;

    /** 商品状态 0下架 1上架 */
    private Integer productStatus;

    /** 是否勾选 0否 1是 */
    private Integer selected;

    /** 小计金额（分） */
    private Integer subtotalAmount;

    public static CartVO from(Cart cart, Product product) {
        CartVO vo = new CartVO();
        vo.setId(cart.getId());
        vo.setProductId(cart.getProductId());
        vo.setQuantity(cart.getQuantity());
        vo.setSelected(cart.getSelected());
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductSpec(product.getSpec());
            vo.setCoverUrl(product.getCoverUrl());
            vo.setPrice(product.getPrice());
            vo.setPointsPrice(product.getPointsPrice());
            vo.setStock(product.getStock());
            vo.setProductStatus(product.getStatus());
            int price = product.getPrice() == null ? 0 : product.getPrice();
            int quantity = cart.getQuantity() == null ? 0 : cart.getQuantity();
            vo.setSubtotalAmount(price * quantity);
        }
        return vo;
    }
}
