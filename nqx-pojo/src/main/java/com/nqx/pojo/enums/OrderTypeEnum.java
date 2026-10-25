package com.nqx.pojo.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 订单类型。
 *
 * <p>回收单与采购单共用一张 {@code biz_order} 主表，用本枚举区分。
 * 好处是后台检索、导出、看板统计、流转日志只需写一套逻辑；
 * 差异部分（状态机、业务动作）通过本枚举分发到各自的处理器。
 */
@Getter
public enum OrderTypeEnum {

    /** 回收单：用户提交废弃纽扣电池，平台审核后回收并发放积分 */
    RECYCLE(1, "回收单", "RC"),

    /** 采购单：用户购买耗材；积分兑换也是采购单，只是支付方式为积分 */
    PURCHASE(2, "采购单", "PC");

    private final Integer code;
    private final String desc;

    /** 订单号前缀。用前缀区分业务，看一眼单号就知道来源，排查问题时很省事 */
    private final String orderNoPrefix;

    OrderTypeEnum(Integer code, String desc, String orderNoPrefix) {
        this.code = code;
        this.desc = desc;
        this.orderNoPrefix = orderNoPrefix;
    }

    public static OrderTypeEnum of(Integer code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 供前端渲染与日志使用 */
    public boolean isRecycle() {
        return this == RECYCLE;
    }
}
