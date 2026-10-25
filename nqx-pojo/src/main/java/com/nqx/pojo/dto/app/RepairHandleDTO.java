package com.nqx.pojo.dto.app;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理端 - 处理维修工单的入参。
 *
 * <h2>为什么用一个 DTO 承载「受理 / 完成 / 驳回」三个动作</h2>
 *
 * <p>三个动作的作用对象、必填字段、校验规则几乎一样，只有目标状态不同。
 * 拆成三个 DTO 会得到三个只差一个 {@code status} 字段的类，
 * 改一处要改三处 —— 这是"形式上的分离"，不是真正的分离。
 *
 * <p>真正需要分开的是**动作语义**：所以 Controller 上是三个独立路径
 * （{@code /accept}、{@code /complete}、{@code /reject}），各自带独立的权限码，
 * 而它们共用一个入参类型。
 *
 * <h2>为什么没有 status 字段</h2>
 *
 * <p>目标状态由**调用的是哪个接口**决定，不由请求体决定。
 * 如果让前端传 status，就等于让前端决定"这个动作能把工单变成什么状态" ——
 * 那是业务规则，必须留在服务端。少一个可传字段，就少一条被越权利用的路径。
 */
@Data
public class RepairHandleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 处理说明。
     *
     * <p>「驳回」时**必填**（用户要知道为什么被驳回，否则只能来问客服）；
     * 「受理 / 完成」时可选。
     *
     * <p>校验放在 Service 里而不是注解上：因为它是"按动作区分是否必填"的条件校验，
     * 用 {@code @NotBlank} 会把"受理时也必须写说明"变成硬性要求，
     * 反而逼着管理员写一堆"已受理"的空话。
     */
    @Size(max = 500, message = "处理说明最多 500 字")
    private String remark;
}
