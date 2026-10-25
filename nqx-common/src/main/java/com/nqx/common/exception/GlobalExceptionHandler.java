package com.nqx.common.exception;

import com.nqx.common.api.Result;
import com.nqx.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p><b>核心价值</b>：让业务代码可以放心地「抛出异常」，而不是层层 try-catch 后手动包装返回值。
 * 所有异常在这里被收敛成统一的 {@link Result} 结构。
 *
 * <p><b>两条设计原则</b>：
 * <ol>
 *   <li><b>业务异常（{@link BizException}）只记 warn 不记堆栈</b> —— 它是可预期的流程分支
 *       （如"积分不足"），不是缺陷。打完整堆栈会淹没日志、且在高频场景下影响性能。</li>
 *   <li><b>未知异常记 error 并打完整堆栈，但绝不把堆栈返回给前端</b> ——
 *       返回堆栈等于把表名、SQL、类路径泄露给攻击者，是典型的安全漏洞。</li>
 * </ol>
 *
 * <p><b>关于 HTTP 状态码</b>：这里统一返回 HTTP 200，业务成败由响应体中的 {@code code} 表达。
 * 这样前端只需判断一个字段，不必同时处理 HTTP 状态码和业务码两套逻辑。
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletRequest request) {
        // 业务异常是可预期的，只记 warn，且不打堆栈（BizException 已覆写 fillInStackTrace）
        log.warn("业务异常 | uri={} | code={} | msg={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    // ==================== 参数校验 ====================

    /** {@code @RequestBody @Valid} 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 | {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /** 表单/查询参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 | {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /** 方法级 {@code @Validated} 校验失败（如 {@code @NotNull} 直接标在 Controller 方法参数上） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("；"));
        log.warn("约束校验失败 | {}", msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "缺少必要参数：" + e.getParameterName();
        log.warn(msg);
        return Result.error(ResultCode.PARAM_ERROR, msg);
    }

    /** 请求体不是合法 JSON，或字段类型不匹配 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 | {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "请求参数格式不正确");
    }

    // ==================== 请求方式与资源 ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 | {}", e.getMessage());
        return Result.error(ResultCode.METHOD_NOT_ALLOWED,
                "请求方法不支持，当前接口支持：" + String.join(",", e.getSupportedMethods() == null
                        ? new String[0] : e.getSupportedMethods()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Content-Type 不支持 | {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR, "不支持的 Content-Type，请使用 application/json");
    }

    /**
     * 静态资源/接口路径不存在。
     * Spring 6.1+ 不再抛 {@code NoHandlerFoundException}，改抛本异常。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在 | {}", e.getResourcePath());
        return Result.error(ResultCode.NOT_FOUND, "接口不存在：" + e.getResourcePath());
    }

    // ==================== 文件上传 ====================

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大 | {}", e.getMessage());
        return Result.error(ResultCode.FILE_SIZE_EXCEEDED);
    }

    /**
     * 请求不是 multipart 形式。
     *
     * <p>典型场景：前端没设置 {@code Content-Type: multipart/form-data}
     * （比如用 axios 直接发 JSON），或调用方用 curl 时忘了 {@code -F}。
     *
     * <p><b>必须归到 400 而不是让它落到 500 兜底里</b>。
     * 这个问题是调用方的用法错误，不是服务端故障 ——
     * 返回"系统繁忙，请稍后重试"会让前端以为要重试，
     * 实际上重试一百次都一样，反而掩盖了真正的原因（参数形式不对）。
     */
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipart(MultipartException e) {
        log.warn("非 multipart 请求 | {}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR,
                "文件上传需以 multipart/form-data 提交，且字段名为 file");
    }

    /**
     * multipart 请求里缺少指定的文件部分。
     *
     * <p>与上面那条的区别：请求确实是 multipart，但字段名不叫 {@code file}。
     * 分开处理是因为两者的修复方式不同（一个改请求方式，一个改字段名）。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("缺少上传文件部分 | part={}", e.getRequestPartName());
        return Result.error(ResultCode.PARAM_ERROR,
                "缺少文件参数：" + e.getRequestPartName());
    }

    // ==================== 兜底 ====================

    /**
     * 未知异常兜底。
     *
     * <p>注意：这里**必须**打完整堆栈（这是排查线上问题的唯一线索），
     * 但**只返回一句通用提示**给前端，绝不返回 {@code e.getMessage()}、类名或堆栈。
     * 那些信息会泄露表结构、SQL 片段与依赖版本，是常见的信息泄露漏洞。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 | uri={} | method={}", request.getRequestURI(), request.getMethod(), e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }
}
