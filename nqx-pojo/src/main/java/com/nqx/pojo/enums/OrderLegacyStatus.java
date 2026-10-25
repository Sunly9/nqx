package com.nqx.pojo.enums;

import java.util.List;

/**
 * 旧版字符串状态 ↔ 新状态码的双向映射。
 *
 * <h2>为什么必须放在一个类里，而不是各写各的</h2>
 *
 * <p>这里踩过一个真实的 500：我最初只在 {@code AppOrderVO} 里写了
 * 「数字码 → 旧字符串」的正向映射（用于输出），却忘了反向。
 * 结果小程序按 tab 切换时把 {@code status=pending_shipment} 当作查询参数发上来，
 * 而后端声明的是 {@code Integer status}，Spring 直接抛
 * {@code MethodArgumentTypeMismatchException} —— 用户看到的是"系统繁忙"。
 *
 * <p><b>只做一半的兼容，比不做更危险</b>：输出对了会让人以为"兼容完成了"，
 * 而输入侧的缺口要等到用户点了某个 tab 才暴露。
 *
 * <p>把两个方向放进同一个类，是为了让「改了正向就必须检查反向」这件事
 * 在结构上变得显眼 —— 打开这个文件就能同时看到两张表。
 *
 * <h2>为什么旧词表有两套</h2>
 *
 * <p>旧库把两类订单拆成了 {@code orders} 与 {@code procurement_orders} 两张表，
 * 它们各自演化出了不同的状态命名：
 * <pre>
 *   回收单：pending / processing / completed / cancelled
 *   采购单：pending_payment / pending_shipment / pending_receipt /
 *           pending_evaluation / completed / cancelled
 * </pre>
 */
public final class OrderLegacyStatus {

    /** tab 的"全部"取值，等价于不加状态过滤 */
    public static final String ALL = "all";

    private OrderLegacyStatus() {
    }

    // ========================================================================
    // 方向一：新状态码 → 旧字符串（用于接口输出，让旧页面能识别）
    // ========================================================================

    /**
     * 数字状态码 → 旧版字符串状态。
     *
     * <p>必须按订单类型分流：两类订单的词表不同，若共用一套，
     * 采购订单页的操作按钮会因为状态值对不上而<b>永远不渲染</b>
     * （页面不报错，只是"没有按钮可点"）。
     */
    public static String toLegacy(OrderTypeEnum type, OrderStatusEnum status) {
        if (status == null) {
            return null;
        }
        if (type == OrderTypeEnum.PURCHASE) {
            return switch (status) {
                case PENDING_PAY -> "pending_payment";
                case PAID -> "pending_shipment";
                case DELIVERED -> "pending_receipt";
                case PURCHASE_FINISHED -> "completed";
                case CANCELLED, CLOSED -> "cancelled";
                default -> "pending_payment";
            };
        }
        return switch (status) {
            case PENDING_AUDIT, AUDIT_PASSED -> "pending";
            case COLLECTED -> "processing";
            case RECYCLE_FINISHED -> "completed";
            /*
             * ⚠️ REJECTED 必须单独映射成 "rejected"，不能并进 "cancelled"。
             *
             * 原来的写法是 `case REJECTED, CANCELLED, CLOSED -> "cancelled"`，
             * 当时的理由是「旧页面没有 rejected 这个词，原样返回会显示英文」。
             * 但那个理由只考虑了**字符串显示**，忽略了**它的副作用**：
             *
             *   页面用 status 决定文案 → 被驳回的订单显示成「订单已取消」。
             *
             * 于是用户看到的是自相矛盾的两句话：
             *   标题「已驳回」 + 描述「订单已取消」。
             * 而且「已取消」暗示是**用户自己取消的**，实际是平台驳回的 ——
             * 这会让用户以为系统出了错，也不会去找驳回原因。
             *
             * 现在 statusText（「已驳回」）与 status 一致，且详情页会展示
             * reject_reason（驳回原因）。旧页面若真出现未知字符串，
             * 也只会显示一个英文词，远好于显示错误的业务含义。
             */
            case REJECTED -> "rejected";
            case CANCELLED, CLOSED -> "cancelled";
            default -> "pending";
        };
    }

    // ========================================================================
    // 方向二：旧字符串 → 新状态码列表（用于接收旧页面传来的筛选参数）
    // ========================================================================

    /**
     * 解析查询参数里的 {@code status}，返回要匹配的状态码列表。
     *
     * <p>同时接受两种输入：
     * <ul>
     *   <li><b>数字码</b>（新客户端 / 管理后台）：{@code "13"} → {@code [13]}；</li>
     *   <li><b>旧字符串</b>（旧小程序）：{@code "pending_shipment"} → {@code [41]}。</li>
     * </ul>
     *
     * <p>返回 {@code null} 表示<b>不加状态过滤</b>，而不是"匹配不到任何状态" ——
     * 这两者的区别很关键：「全部」tab 与「一个非法值」都应该展示全部，
     * 而不是给用户一个空列表让他以为订单丢了。
     *
     * <p>有些旧字符串对应多个新状态码（例如 {@code pending} 同时覆盖
     * 待审核与待回收），所以返回的是列表而不是单个码。
     *
     * @param orderType 订单类型，用于区分 completed 在两类订单里的含义（可空）
     */
    public static List<Integer> toCodes(String status, Integer orderType) {
        if (status == null || status.isBlank() || ALL.equalsIgnoreCase(status.trim())) {
            return null;
        }
        String value = status.trim();

        // 纯数字：新客户端直接传状态码
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return List.of(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        boolean purchase = orderType != null && orderType == 2;
        return switch (value.toLowerCase()) {
            // ---- 回收单词表 ----
            // 待审核 + 待回收 都属于"进行中但还没上门"
            case "pending" -> List.of(
                    OrderStatusEnum.PENDING_AUDIT.getCode(),
                    OrderStatusEnum.AUDIT_PASSED.getCode());
            // 已回收、待结算
            case "processing" -> List.of(OrderStatusEnum.COLLECTED.getCode());

            // 已驳回：以前并入 cancelled 一起筛，现在独立成一个 tab
            case "rejected" -> List.of(OrderStatusEnum.REJECTED.getCode());

            // ---- 采购单词表 ----
            case "pending_payment" -> List.of(OrderStatusEnum.PENDING_PAY.getCode());
            case "pending_shipment" -> List.of(OrderStatusEnum.PAID.getCode());
            case "pending_receipt" -> List.of(OrderStatusEnum.DELIVERED.getCode());
            // 「待评价」在旧版是一个独立状态；新版里订单收货即完成，
            // 是否已评价是另一张表的事，所以这里与 completed 同一个码
            case "pending_evaluation" -> List.of(OrderStatusEnum.PURCHASE_FINISHED.getCode());

            // ---- 两端共有 ----
            case "completed" -> purchase
                    ? List.of(OrderStatusEnum.PURCHASE_FINISHED.getCode())
                    : List.of(OrderStatusEnum.RECYCLE_FINISHED.getCode(),
                              OrderStatusEnum.PURCHASE_FINISHED.getCode());
            // 已取消与已关闭在旧页面里是同一个 tab
            case "cancelled" -> List.of(
                    OrderStatusEnum.CANCELLED.getCode(),
                    OrderStatusEnum.CLOSED.getCode());

            // 未知取值一律当作"全部"。
            // 宁可多展示，也不要因为一个拼错的状态值让用户看到空列表 ——
            // 那会让人以为数据丢了，比报错更难排查。
            default -> null;
        };
    }
}
