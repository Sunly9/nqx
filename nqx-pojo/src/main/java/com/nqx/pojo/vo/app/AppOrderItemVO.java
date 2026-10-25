package com.nqx.pojo.vo.app;

import com.nqx.pojo.entity.OrderItem;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单商品明细（小程序端）。
 *
 * <h2>字段名与单位都对齐旧页面</h2>
 *
 * <p>旧版采购订单页的模板：
 * <pre>{@code
 * <image src="{{prod.image}}">
 * <view>{{prod.name}}</view>
 * <text>¥{{prod.price}}</text>
 * <text>x{{prod.count}}</text>
 * }</pre>
 *
 * <p>三处需要注意：
 * <ul>
 *   <li>{@code image} —— 实体里叫 {@code itemCover}；</li>
 *   <li>{@code price} —— 库存的是<b>分</b>，页面用 {@code ¥} 展示所以必须是<b>元</b>；</li>
 *   <li>{@code count} —— 实体里叫 {@code quantity}，<b>名字不一样</b>，
 *       写成 {@code quantity} 页面就显示不出数量。</li>
 * </ul>
 *
 * <p>这正是"一个字段名对不上就白屏"的典型：模板里是 {@code prod.count}，
 * 少一个字符数量就永远是空的，而且不会报错。
 */
@Data
public class AppOrderItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品 ID（也是模板 wx:key 用的字段） */
    private Long id;

    private String name;

    /** 商品封面图，旧字段名 */
    private String image;

    /** 单价，单位<b>元</b>（新库存的是分，这里除以 100） */
    private BigDecimal price;

    /** 购买数量，旧字段名是 count 而不是 quantity */
    private Integer count;

    public static AppOrderItemVO from(OrderItem item) {
        AppOrderItemVO vo = new AppOrderItemVO();
        // 明细里存的是 item_id（商品主键）
        vo.setId(item.getItemId());
        vo.setName(item.getItemName());
        vo.setImage(item.getItemCover());
        vo.setPrice(toYuan(item.getPrice()));
        vo.setCount(item.getQuantity());
        return vo;
    }

    /**
     * 分 → 元。
     *
     * <p>与 {@code ProductAppVO} 用同一套做法：{@code movePointLeft(2)}
     * 的语义就是"小数点左移两位"，不涉及除法精度问题。
     */
    private static BigDecimal toYuan(Integer cents) {
        if (cents == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
