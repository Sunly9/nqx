package com.nqx.pojo.vo.app;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.RepairApply;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 管理端 - 维修工单列表项 / 详情。
 *
 * <h2>为什么不复用 {@link RepairApplyVO}</h2>
 *
 * <p>那个类是小程序端的契约：字段名被固定成下划线（{@code apply_no}、
 * {@code status_text}），因为旧页面就是这么读的。改它会让手机端静默失效。
 *
 * <p>而管理端需要的东西本来就不一样：
 * <ul>
 *   <li>需要**申请人昵称**（客服要认人，光有 userId 没有意义）；</li>
 *   <li>需要**处理人**与**处理时间**（追责与统计工单时效）；</li>
 *   <li>不需要 {@code type} 那种别名式字段（管理端直接用 {@code warrantyType} 更清楚）。</li>
 * </ul>
 *
 * <p>用两个 VO 表达两种视图，比一个 VO 加一堆可选字段更诚实。
 *
 * <h2>为什么不直接返回实体</h2>
 *
 * <p>实体里有 {@code deleted}（逻辑删除标记）和 {@code userId}（已经有了昵称）。
 * 更要紧的是：实体新增字段会自动出现在接口上，而没人会回头检查
 * "这个新字段该不该给管理端看"。显式列字段，让"不该给的"默认不给。
 */
@Data
public class AdminRepairApplyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 申请单号 */
    private String applyNo;

    /** 申请人 ID */
    private Long userId;

    /** 申请人昵称（LEFT JOIN app_user 取得，用户被删时为空但记录不消失） */
    private String nickname;

    /** 关联订单 ID */
    private Long orderId;

    /** 关联订单号（LEFT JOIN biz_order 取得，便于客服直接查原单） */
    private String orderNo;

    private Long productId;

    private String productName;

    /** 故障描述 */
    private String faultDesc;

    /** 故障图片（已解析成数组） */
    private List<String> images;

    /**
     * 故障图片的**原始 JSON 字符串**（库里的形态）。
     *
     * <p><b>为什么需要它</b>：列表查询由 MyBatis 直接按列映射到本 VO，
     * 而 {@code repair_apply.images} 库里是 JSON 字符串。MyBatis 不会自动
     * 把 {@code ["a.jpg"]} 转成 {@code List<String>}，直接把列名映射到
     * {@code images} 上会得到 **null**（而且是静默的）。
     *
     * <p>所以映射关系是：列 → {@code imagesRaw}（String），再由 Service
     * 解析进 {@code images}（List）。这样两个形态各归其位，
     * 不依赖 MyBatis 的类型推断。
     *
     * <p>{@code @JsonIgnore}：这个字段只是传输中间态，不该输出给前端 ——
     * 前端要的是解析好的数组。
     */
    @JsonIgnore
    private String imagesRaw;

    private String contactName;

    private String contactPhone;

    private String address;

    /** 状态 1待处理 2处理中 3已完成 4已驳回 */
    private Integer status;

    /** 状态文案 */
    private String statusText;

    /**
     * 保修判定：warranty / paid / null（历史数据未判定）。
     *
     * <p>这是管理员处理工单时最需要的一条信息：保修期内免费修，过保要先报价。
     */
    private String warrantyType;

    /** 保修判定的中文说明，避免前端再维护一份映射 */
    private String warrantyTypeText;

    /** 处理说明（驳回原因 / 完成情况） */
    private String handleRemark;

    /** 处理人（管理员）ID */
    private Long handlerId;

    /** 处理时间 */
    private LocalDateTime handleTime;

    @JsonProperty("create_time")
    private LocalDateTime createTime;

    public static AdminRepairApplyVO from(RepairApply apply) {
        AdminRepairApplyVO vo = new AdminRepairApplyVO();
        vo.setId(apply.getId());
        vo.setApplyNo(apply.getApplyNo());
        vo.setUserId(apply.getUserId());
        vo.setOrderId(apply.getOrderId());
        vo.setProductId(apply.getProductId());
        vo.setProductName(apply.getProductName());
        vo.setFaultDesc(apply.getFaultDesc());
        vo.setImages(parseImages(apply.getImages()));
        vo.setContactName(apply.getContactName());
        vo.setContactPhone(apply.getContactPhone());
        vo.setAddress(apply.getAddress());
        vo.setStatus(apply.getStatus());
        vo.setStatusText(RepairApplyVO.statusText(apply.getStatus()));
        vo.setWarrantyType(apply.getWarrantyType());
        vo.setWarrantyTypeText(warrantyTypeText(apply.getWarrantyType()));
        vo.setHandleRemark(apply.getHandleRemark());
        vo.setHandlerId(apply.getHandlerId());
        vo.setHandleTime(apply.getHandleTime());
        vo.setCreateTime(apply.getCreateTime());
        return vo;
    }

    /**
     * 补齐派生字段。
     *
     * <p><b>为什么必须有这一步</b>：列表查询走的是
     * {@code RepairApplyMapper.selectAdminPage}，结果由 MyBatis 按列名直接
     * 映射到本 VO —— **它不会调用 {@link #from(RepairApply)}**。
     * 因此 {@code statusText}、{@code warrantyTypeText}、{@code images}
     * 这些"算出来的"字段在列表里全是 null。
     *
     * <p>这个 bug 的隐蔽之处在于：接口返回 200、字段一个不少地"存在"（只是值为 null），
     * 前端写 {@code row.statusText || '-'} 就安静地显示成「-」。
     * 我是通过**实际看一眼响应体**才发现列表里少了这两个字段的 ——
     * 编译通过、页面不报错，都说明不了问题。
     */
    public void fillDerivedFields() {
        this.statusText = RepairApplyVO.statusText(this.status);
        this.warrantyTypeText = warrantyTypeText(this.warrantyType);
        if (this.imagesRaw != null) {
            this.images = parseImages(this.imagesRaw);
        }
    }

    /**
     * 保修判定的中文说明。
     *
     * <p>放在后端而不是让前端维护映射：那份表迟早会和后端取值脱节，
     * 而脱节的表现是"页面上显示 warranty 这个英文单词"或者一片空白。
     */
    public static String warrantyTypeText(String warrantyType) {
        if (warrantyType == null) {
            // 历史数据（本字段是后加的）没有判定结果。显示"未判定"而不是"已过保"——
            // "不知道"和"确认过保"是两件事，混为一谈会让客服对用户说出错误结论。
            return "未判定";
        }
        return switch (warrantyType) {
            case RepairApplyVO.TYPE_WARRANTY -> "保修期内（免费）";
            case RepairApplyVO.TYPE_PAID -> "已过保（需报价）";
            default -> warrantyType;
        };
    }

    private static List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> list = JSONUtil.toList(imagesJson, String.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
