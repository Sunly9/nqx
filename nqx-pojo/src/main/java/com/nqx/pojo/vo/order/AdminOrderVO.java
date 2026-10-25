package com.nqx.pojo.vo.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 后台订单列表项。
 *
 * <p>这是给<b>新的 Vue 管理后台</b>用的，因此字段命名用标准的驼峰即可，
 * 不必像小程序端 VO 那样迁就旧前端的下划线命名 ——
 * 兼容旧客户端的脏活只应存在于需要兼容的那一侧。
 *
 * <p>比实体多出 {@code nickname}（来自 JOIN app_user）与 {@code statusText}
 * （由状态枚举生成），这样后台表格不用再发额外请求、也不用自己维护状态文案表。
 */
@Data
public class AdminOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Long userId;

    /** 下单用户昵称，来自 JOIN app_user */
    private String nickname;

    private Integer orderType;

    private String orderTypeText;

    /** 回收方式 send / pickup */
    private String recycleMethod;

    private Integer status;

    private String statusText;

    /** 预估重量（kg） */
    private BigDecimal estimatedWeight;

    /** 实际重量（kg） */
    private BigDecimal actualWeight;

    /** 本次获得积分 */
    private Integer totalPoints;

    /** 碳减排量（kg） */
    private BigDecimal carbonSaved;

    private String contactName;

    private String contactPhone;

    private String courierNo;

    /**
     * 买家最近一次「提醒发货」的时间（为 null 表示从未提醒过）。
     *
     * <p>这是买家在催，不是发货 —— 管理员看到它就知道该优先处理这一单。
     */
    private LocalDateTime remindShipAt;

    /** 买家累计提醒发货次数 */
    private Integer remindShipCount;

    /**
     * 后台列表展示文案（派生字段，不是数据库列）。
     *
     * <p>为什么不在前端拼：「催 2 次」和「10-21 16:30 催过」的拼接规则只应该有一处，
     * 否则列表、详情、导出三处各写一遍，迟早不一致。
     *
     * <p>返回 null（而非空串）让 Jackson 的 non_null 策略直接省略该字段，
     * 前端用 {@code v-if} 判断即可。
     */
    public String getRemindShipText() {
        if (remindShipAt == null) {
            return null;
        }
        int count = remindShipCount == null || remindShipCount <= 0 ? 1 : remindShipCount;
        String time = remindShipAt.format(REMIND_TIME_FORMAT);
        return count > 1 ? ("催 " + count + " 次 · " + time) : ("催过 · " + time);
    }

    private static final DateTimeFormatter REMIND_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @JsonProperty("createTime")
    private LocalDateTime createTime;

    private LocalDateTime finishTime;
}
