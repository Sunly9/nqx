package com.nqx.web.handler;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.nqx.common.api.Result;
import com.nqx.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 认证授权异常处理器。
 *
 * <p><b>为什么必须显式指定最高优先级？</b>
 * Spring 的 {@code ExceptionHandlerExceptionResolver} 会按顺序遍历所有
 * {@code @ControllerAdvice}，<b>取的第一个「能解析该异常」的 advice</b>。
 * 而 {@code GlobalExceptionHandler} 里有一个 {@code @ExceptionHandler(Exception.class)}
 * 的兜底方法，它能匹配包括 {@code NotLoginException} 在内的任何异常。
 * 若它排在前面，未登录就会被包装成 500「系统繁忙」，而不是 401「请先登录」——
 * 前端因此无法识别需要跳转登录页，问题还极难定位。
 *
 * <p>因此这里用 {@link Ordered#HIGHEST_PRECEDENCE} 确保本处理器先被匹配到；
 * 当异常不是 Sa-Token 异常时，本类没有对应方法，会自然落到兜底处理器。
 *
 * <p><b>日志级别</b>：401/403 是正常的业务预期（token 过期、越权尝试），
 * 用 {@code warn} 而非 {@code error}，避免污染错误日志、触发无效告警。
 * 但会把 URI 一并记录，便于发现恶意的越权探测行为。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SaTokenExceptionHandler {

    /** 未登录 / token 无效 / token 已过期 */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e, HttpServletRequest request) {
        log.warn("未登录访问 | uri={} | type={}", request.getRequestURI(), e.getType());

        // 按 Sa-Token 的失败类型给出更精确的提示，前端可据此决定「跳登录页」还是「静默重登」
        String msg = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供登录凭证，请先登录";
            case NotLoginException.INVALID_TOKEN -> "登录凭证无效，请重新登录";
            case NotLoginException.TOKEN_TIMEOUT -> "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "账号已在其他设备登录";
            case NotLoginException.KICK_OUT -> "账号已被管理员强制下线";
            default -> ResultCode.UNAUTHORIZED.getMsg();
        };
        return Result.error(ResultCode.UNAUTHORIZED, msg);
    }

    /** 已登录但缺少所需权限码 */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermission(NotPermissionException e, HttpServletRequest request) {
        log.warn("越权访问(权限码) | uri={} | 缺少权限={}", request.getRequestURI(), e.getPermission());
        return Result.error(ResultCode.FORBIDDEN, "缺少权限：" + e.getPermission());
    }

    /** 已登录但角色不符 */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e, HttpServletRequest request) {
        log.warn("越权访问(角色) | uri={} | 缺少角色={}", request.getRequestURI(), e.getRole());
        return Result.error(ResultCode.FORBIDDEN, "缺少角色：" + e.getRole());
    }

    /** 账号被禁用 */
    @ExceptionHandler(DisableServiceException.class)
    public Result<Void> handleDisabled(DisableServiceException e, HttpServletRequest request) {
        log.warn("禁用账号访问 | uri={} | loginType={}", request.getRequestURI(), e.getLoginType());
        return Result.error(ResultCode.USER_DISABLED);
    }
}
