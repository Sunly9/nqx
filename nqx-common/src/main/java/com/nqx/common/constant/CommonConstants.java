package com.nqx.common.constant;

/**
 * 通用常量。
 *
 * <p>用接口而非类来承载常量：接口字段天然是 {@code public static final}，
 * 且能被其他类直接实现（虽然不推荐），语义上更贴合「常量集合」。
 */
public interface CommonConstants {

    // ==================== 链路追踪 ====================

    /** MDC 中的 traceId 键名，logback 通过 %X{traceId} 输出 */
    String TRACE_ID = "traceId";

    /** 前端透传 traceId 的请求头 */
    String TRACE_ID_HEADER = "X-Trace-Id";

    // ==================== 认证 ====================

    String AUTHORIZATION = "Authorization";
    String BEARER_PREFIX = "Bearer ";

    // ==================== 逻辑删除与状态 ====================

    int DELETED_NO = 0;
    int DELETED_YES = 1;

    int STATUS_DISABLED = 0;
    int STATUS_ENABLED = 1;

    // ==================== 分页 ====================

    long PAGE_NUM_DEFAULT = 1L;
    long PAGE_SIZE_DEFAULT = 10L;

    /** 单页最大条数上限，防止前端传入 size=999999 拖垮数据库 */
    long PAGE_SIZE_MAX = 100L;

    // ==================== 操作人类型 ====================
    //
    // 【全局统一约定】1 小程序用户 / 2 管理员 / 3 系统
    //   - sys_operation_log.user_type
    //   - sys_login_log.user_type
    //   - order_flow.operator_type
    // 三处共用同一个约定，避免"同一件事在不同表里存了不同的值"。
    //
    // 注：V1 建表时这两张日志表的列注释误写成了「1管理员 2小程序用户」，与 order_flow
    // 相反。已由 V4 迁移统一修正为上述约定，实体与代码无需改动（代码本就是按此约定写的）。

    int OPERATOR_TYPE_USER = 1;
    int OPERATOR_TYPE_ADMIN = 2;
    int OPERATOR_TYPE_SYSTEM = 3;

    // ==================== 业务参数 ====================

    /** 采购单未支付自动关闭时长（分钟），与 docs/03 §3.2 的延迟队列保持一致 */
    int ORDER_TIMEOUT_MINUTES = 30;

    /** 订单号前缀 */
    String ORDER_NO_PREFIX_RECYCLE = "RC";
    String ORDER_NO_PREFIX_PURCHASE = "PC";
}
