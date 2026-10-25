package com.nqx.pojo.dto.points;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员调整积分入参。
 */
@Data
public class PointsAdjustDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "请选择用户")
    @JsonProperty("user_id")
    @JsonAlias("userId")
    private Long userId;

    /**
     * 调整值：正数为增加、负数为扣减，不能为 0。
     *
     * <p>用带符号的单一字段而不是"direction + value"两个字段：
     * 后者会出现"direction=增加、value=-50"这种自相矛盾的输入，
     * 而单一有符号数天然不存在这种状态。
     */
    @NotNull(message = "请填写调整积分")
    private Integer points;

    @NotBlank(message = "请填写调整原因")
    @Size(max = 200, message = "调整原因不能超过 200 个字符")
    private String remark;

    /**
     * 幂等键（调用方生成）。
     *
     * <p>管理员在后台点「确认调整」时，网络抖动导致的重试、
     * 或手抖双击，都会发出两次完全相同的请求。
     * 若不做幂等，用户就会被重复加分或重复扣分 ——
     * 而这类错误往往要等用户来投诉才会被发现。
     *
     * <p>调用方每次操作生成一个唯一值（如 UUID），重试时沿用同一个值即可。
     * 后端把它作为 {@code points_record.biz_no}，由唯一索引兜底。
     * 不传时后端会自动生成一个，此时**不保证幂等** ——
     * 这个取舍是明确的：要么调用方承担生成责任，要么放弃幂等保证。
     */
    @JsonProperty("request_id")
    @JsonAlias("requestId")
    @Size(max = 64, message = "幂等键不能超过 64 个字符")
    private String requestId;
}
