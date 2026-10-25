package com.nqx.common.exception;

import com.nqx.common.api.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p><b>为什么继承 {@link RuntimeException} 而不是 {@link Exception}？</b>
 * Spring 的 {@code @Transactional} 默认只对 {@code RuntimeException} 和 {@code Error}
 * 回滚。若继承受检异常，必须显式写 {@code rollbackFor = Exception.class}，
 * 一旦漏写就会出现「抛了异常但数据已提交」的严重问题。
 * 选择运行时异常是让默认行为就是安全的。
 *
 * <p>本异常由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@link com.nqx.common.api.Result}，
 * 因此业务代码里**不需要**层层 try-catch，抛出即可。
 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务错误码 */
    private final Integer code;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMsg());
        this.code = resultCode.getCode();
    }

    /**
     * 使用统一错误码，但用更具体的文案覆盖默认提示。
     * 例：{@code new BizException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH, "「CR2032 电池」库存不足")}
     */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /** 断言为真，否则抛出业务异常。用于替代大量 if-throw */
    public static void assertTrue(boolean condition, ResultCode resultCode) {
        if (!condition) {
            throw new BizException(resultCode);
        }
    }

    public static void assertNotNull(Object obj, ResultCode resultCode) {
        if (obj == null) {
            throw new BizException(resultCode);
        }
    }

    /**
     * 业务失败不必记录完整堆栈 —— 这是可预期的流程分支，不是缺陷。
     * 覆写此方法可让日志更干净，也避免高频业务异常带来的性能损耗。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
