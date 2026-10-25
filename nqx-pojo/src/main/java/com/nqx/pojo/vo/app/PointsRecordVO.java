package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.PointsRecord;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分流水（小程序端）。
 *
 * <h2>字段名为什么同时有新旧两套</h2>
 *
 * <p>旧版积分页读的是 {@code points_history} 表的原始列：
 * <pre>{@code
 * desc: item.description,
 * type: item.type,     // 'gain' | 'spend'
 * amount: item.amount,
 * }</pre>
 *
 * <p>而新表字段语义不同（{@code change_value} 带符号、{@code change_type} 是业务类型），
 * 因此这里做一层显式映射：
 * <ul>
 *   <li>{@code type} —— 方向（gain/spend），对应旧字段，页面直接可用；</li>
 *   <li>{@code amount} —— 变动绝对值，对应旧字段（旧版增减都是正数，方向靠 type 区分）；</li>
 *   <li>{@code description} —— 备注，对应旧字段；</li>
 *   <li>{@code change_value} / {@code change_type} / {@code balance_after} —— 新字段，
 *       供新版页面与对账使用。</li>
 * </ul>
 * 多带几个字段不会让旧页面出错，但少一个就会显示空白 —— 所以宁可给全。
 * 等旧页面全部迁移完成后，可以删掉前三个兼容字段。
 */
@Data
public class PointsRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 方向 gain=收入 spend=支出（兼容旧字段名） */
    private String type;

    /** 变动绝对值（兼容旧字段名） */
    private Integer amount;

    /** 备注说明（兼容旧字段名） */
    private String description;

    /** 带符号的变动值：正为增、负为减 */
    @JsonProperty("change_value")
    private Integer changeValue;

    /** 变动类型码，见 {@code PointsChangeTypeEnum} */
    @JsonProperty("change_type")
    private Integer changeType;

    /** 变动类型中文说明 */
    @JsonProperty("change_type_text")
    private String changeTypeText;

    /** 变动后余额，用于对账 */
    @JsonProperty("balance_after")
    private Integer balanceAfter;

    /** 关联业务单号 */
    @JsonProperty("biz_no")
    private String bizNo;

    @JsonProperty("created_at")
    private LocalDateTime createTime;

    public static PointsRecordVO from(PointsRecord record) {
        PointsRecordVO vo = new PointsRecordVO();
        vo.setId(record.getId());

        int value = record.getChangeValue() == null ? 0 : record.getChangeValue();
        vo.setChangeValue(value);
        vo.setType(value < 0 ? "spend" : "gain");
        // 旧版增减都存正数、方向由 type 表达，这里保持同样的语义
        vo.setAmount(Math.abs(value));

        vo.setDescription(record.getRemark());
        vo.setChangeType(record.getChangeType());
        vo.setBalanceAfter(record.getBalanceAfter());
        vo.setBizNo(record.getBizNo());
        vo.setCreateTime(record.getCreateTime());

        PointsChangeTypeEnum typeEnum = PointsChangeTypeEnum.of(record.getChangeType());
        vo.setChangeTypeText(typeEnum == null ? "其它" : typeEnum.getDesc());
        return vo;
    }
}
