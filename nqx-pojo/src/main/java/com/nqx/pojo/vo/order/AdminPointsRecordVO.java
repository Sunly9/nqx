package com.nqx.pojo.vo.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台积分流水列表项。
 *
 * <p>比小程序端的 {@code PointsRecordVO} 多了 {@code nickname}（来自 JOIN app_user）。
 * 后台运营需要一眼看出"这笔积分是谁的"，只给 userId 还得再去查一次。
 *
 * <p>注意这里<b>没有</b>为了兼容旧前端而下划线的字段名 ——
 * 管理后台是全新的 Vue 前端，用标准驼峰即可。
 * 兼容旧客户端的脏活只应存在于需要兼容的那一侧。
 */
@Data
public class AdminPointsRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 用户昵称，来自 JOIN app_user */
    private String nickname;

    private Integer changeType;

    /** 变动类型中文说明，由后端枚举补 */
    private String changeTypeText;

    /** 带符号的变动值 */
    private Integer changeValue;

    /** 变动后余额 */
    private Integer balanceAfter;

    private String bizNo;

    private String remark;

    private LocalDateTime createTime;

    /**
     * 补齐枚举文案。
     *
     * <p>在 Java 侧补而不是在 SQL 里写 {@code CASE WHEN}：
     * 否则同一套类型文案会同时存在于 SQL 与枚举两处，
     * 改一处漏一处就会让列表与筛选下拉框显示不一致。
     */
    public void fillChangeTypeText() {
        PointsChangeTypeEnum type = PointsChangeTypeEnum.of(this.changeType);
        this.changeTypeText = type == null ? "其它" : type.getDesc();
    }
}
