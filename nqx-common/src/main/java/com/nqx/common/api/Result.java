package com.nqx.common.api;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回体。
 *
 * <p>所有接口（含异常）都返回这个结构，前端只需判断一个 {@code code} 字段，
 * 不需要为每个接口写不同的错误处理分支。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code == 200} 表示业务成功，其余一律为失败；</li>
 *   <li>HTTP 状态码始终为 200（除框架级错误外），业务成败由 {@code code} 表达，
 *       避免前端既要判断 HTTP 状态码又要判断业务码；</li>
 *   <li>{@code timestamp} 便于排查前端缓存问题。</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，200 为成功 */
    private Integer code;

    /** 提示信息，可直接展示给用户 */
    private String msg;

    /** 业务数据，失败时为 null */
    private T data;

    /** 服务端时间戳（毫秒） */
    private Long timestamp;

    private Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 成功 ====================

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), msg, data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /**
     * 使用统一错误码，但覆盖提示信息。
     * 适用于「同一类错误需要补充上下文」的场景，例如库存不足时提示具体商品名。
     */
    public static <T> Result<T> error(ResultCode resultCode, String msg) {
        return new Result<>(resultCode.getCode(), msg, null);
    }

    public static <T> Result<T> error(String msg) {
        return new Result<>(ResultCode.SYSTEM_ERROR.getCode(), msg, null);
    }

    public static <T> Result<T> error(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }

    /** 供 AOP、拦截器等需要判断的场景使用 */
    public boolean isSuccess() {
        return ResultCode.SUCCESS.getCode().equals(this.code);
    }
}
