package com.nqx.pojo.vo.app;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单收货地址（小程序端）。
 *
 * <h2>为什么是一个嵌套对象，而不是三个扁平字段</h2>
 *
 * <p>旧版小程序的采购订单页读的是<b>嵌套结构</b>：
 * <pre>{@code
 * {{order.address.name}}     收货人
 * {{order.address.phone}}    联系电话
 * {{order.address.address}}  详细地址
 * }</pre>
 *
 * <p>而回收单详情页读的是<b>扁平字符串</b>：{@code {{order.address}}}。
 * 同一个字段名、两种形状 —— 这源于旧库把两类订单拆成了
 * {@code orders} 与 {@code procurement_orders} 两张表，列的结构本来就不同。
 *
 * <h2>为什么最终选了「统一成对象 + 改一行页面」</h2>
 *
 * <p>另外两个方案都被否决了：
 * <ul>
 *   <li><b>按订单类型返回不同形状</b>（回收单给字符串、采购单给对象）——
 *       能让页面一行不改，但同一个字段的类型随数据变化，
 *       是接口设计里应当极力避免的形态：调用方没法写类型，
 *       排查时也无法从字段名推断内容。</li>
 *   <li><b>再加一个扁平字段</b>（如 {@code address_text}）——
 *       会让"同一个含义有两个字段"，且旧页面读的还是那个错的。</li>
 * </ul>
 *
 * <p>所以统一成对象，并<b>只改回收单详情页的一行模板</b>
 * （{@code {{order.address}}} → {@code {{order.address.address}}}）。
 * 用一个页面的一行改动，换来一个形状一致的接口，这笔账很划算。
 */
@Data
public class OrderAddressVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 收货人 / 联系人 */
    private String name;

    /** 联系电话 */
    private String phone;

    /** 完整地址文本（省市区 + 详细地址） */
    private String address;

    public static OrderAddressVO of(String name, String phone, String address) {
        OrderAddressVO vo = new OrderAddressVO();
        vo.setName(name);
        vo.setPhone(phone);
        vo.setAddress(address);
        return vo;
    }
}
