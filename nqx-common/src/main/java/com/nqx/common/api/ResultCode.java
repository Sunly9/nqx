package com.nqx.common.api;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p><b>为什么用枚举集中管理，而不是散落的字符串或数字？</b>
 * <ol>
 *   <li>错误码聚集在一处，能一眼看出全系统有哪些失败场景，便于查重、避免撞码；</li>
 *   <li>业务代码写 {@code throw new BizException(ResultCode.POINTS_NOT_ENOUGH)}，
 *       不会出现 {@code throw new RuntimeException("积分不足")} 这种无法被前端识别的错误；</li>
 *   <li>提示文案可统一修改，不必全文搜索。</li>
 * </ol>
 *
 * <p><b>分段规则</b>（便于定位问题归属模块）：
 * <pre>
 *   2xx    通用/框架级
 *   10xxx  认证与用户
 *   20xxx  商品与回收品类
 *   30xxx  订单与购物车、地址
 *   40xxx  积分
 *   50xxx  每日问答
 *   60xxx  通用业务（幂等、文件上传等）
 * </pre>
 */
@Getter
public enum ResultCode {

    // ==================== 通用 ====================
    SUCCESS(200, "操作成功"),
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),

    // ==================== 认证与用户 10xxx ====================
    USER_NOT_FOUND(10001, "用户不存在"),
    USER_DISABLED(10002, "账号已被禁用，请联系管理员"),
    PASSWORD_ERROR(10003, "账号或密码错误"),
    USERNAME_EXISTS(10004, "该账号已存在"),
    PHONE_EXISTS(10005, "该手机号已被绑定"),
    WX_LOGIN_FAILED(10006, "微信登录失败，请重试"),
    ROLE_TYPE_INVALID(10007, "无效的用户身份"),
    OLD_PASSWORD_ERROR(10008, "原密码不正确"),
    USER_NOT_LOGIN(10009, "请先登录"),
    ROLE_NOT_FOUND(10010, "角色不存在"),
    ROLE_IN_USE(10011, "该角色已分配给管理员，无法删除"),
    ROLE_CODE_EXISTS(10012, "角色标识已存在"),
    CANNOT_DELETE_SELF(10013, "不能删除当前登录账号"),
    SUPER_ADMIN_PROTECTED(10014, "超级管理员账号受保护，不允许该操作"),

    // ==================== 商品与回收品类 20xxx ====================
    PRODUCT_NOT_FOUND(20001, "商品不存在"),
    PRODUCT_OFF_SHELF(20002, "商品已下架"),
    PRODUCT_STOCK_NOT_ENOUGH(20003, "商品库存不足"),
    CATEGORY_NOT_FOUND(20004, "商品分类不存在"),
    CATEGORY_HAS_CHILDREN(20005, "该分类下存在子分类，无法删除"),
    CATEGORY_HAS_PRODUCT(20006, "该分类下存在商品，无法删除"),
    RECYCLE_CATEGORY_NOT_FOUND(20007, "回收品类不存在"),
    EXCHANGE_LIMIT_EXCEEDED(20008, "超过该商品的每人限兑数量"),
    PRODUCT_NOT_SUPPORT_EXCHANGE(20009, "该商品不支持积分兑换"),
    PRODUCT_NOT_SUPPORT_PURCHASE(20010, "该商品不支持直接购买"),

    // ==================== 评价与维修 21xxx ====================
    REVIEW_ALREADY_EXISTS(21001, "该订单中的商品已评价过"),
    REVIEW_ORDER_NOT_FINISHED(21002, "订单完成后才能评价"),
    REVIEW_PRODUCT_NOT_IN_ORDER(21003, "该商品不在订单中，无法评价"),
    REPAIR_APPLY_NOT_FOUND(21004, "维修申请不存在"),
    REPAIR_ORDER_NOT_FOUND(21005, "维修申请关联的订单不存在"),
    REPAIR_ALREADY_PROCESSED(21006, "该维修申请已处理，无法重复操作"),

    // ==================== 订单 30xxx ====================
    ORDER_NOT_FOUND(30001, "订单不存在"),
    ORDER_NOT_BELONG_TO_USER(30002, "无权操作他人的订单"),
    ORDER_STATUS_ILLEGAL(30003, "当前订单状态不允许该操作"),
    ORDER_CANNOT_CANCEL(30004, "订单已进入履约流程，无法取消"),
    ORDER_ALREADY_PAID(30005, "订单已支付，请勿重复支付"),
    ORDER_TYPE_INVALID(30006, "无效的订单类型"),
    CART_EMPTY(30007, "请先选择要结算的商品"),
    CART_ITEM_NOT_FOUND(30008, "购物车中不存在该商品"),
    ADDRESS_NOT_FOUND(30009, "收货地址不存在"),
    ADDRESS_NOT_BELONG_TO_USER(30010, "无权操作他人的收货地址"),
    RECYCLE_ORDER_ITEM_REQUIRED(30011, "请至少选择一种回收品类"),
    PURCHASE_ADDRESS_REQUIRED(30012, "请先选择收货地址"),

    // ==================== 积分 40xxx ====================
    POINTS_NOT_ENOUGH(40001, "积分不足"),
    POINTS_ACCOUNT_NOT_FOUND(40002, "积分账户不存在"),
    POINTS_RECORD_DUPLICATE(40003, "该笔积分已处理，请勿重复提交"),
    POINTS_ADJUST_VALUE_INVALID(40004, "积分调整值不能为 0"),
    POINTS_ADJUST_REASON_REQUIRED(40005, "请填写积分调整原因"),

    // ==================== 每日问答 50xxx ====================
    QUESTION_NOT_FOUND(50001, "题目不存在"),
    QUESTION_NOT_PUBLISHED(50002, "该题目尚未发布"),
    QUESTION_ALREADY_ANSWERED(50003, "您已作答过该题目"),
    ANSWER_FORMAT_INVALID(50004, "答案格式不正确"),
    QUESTION_IS_ANSWERED(50005, "该题目已有用户作答，无法删除"),

    // ==================== 通用业务 60xxx ====================
    REPEAT_SUBMIT(60001, "请求过于频繁，请勿重复提交"),
    FILE_UPLOAD_FAILED(60002, "文件上传失败"),
    FILE_TYPE_NOT_ALLOWED(60003, "不支持的文件类型"),
    FILE_SIZE_EXCEEDED(60004, "文件大小超出限制"),
    FILE_EMPTY(60005, "上传文件不能为空"),

    // ==================== 账号安全 61xxx ====================
    TOO_MANY_LOGIN_ATTEMPTS(61001, "登录失败次数过多，请稍后再试"),
    ACCOUNT_LOCKED(61002, "账号已被临时锁定，请稍后再试");

    private final Integer code;
    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
