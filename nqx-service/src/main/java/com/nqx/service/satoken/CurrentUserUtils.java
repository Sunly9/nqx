package com.nqx.service.satoken;

import com.nqx.common.util.ServletUtils;
import com.nqx.common.constant.CommonConstants;

/**
 * 当前登录用户工具。
 *
 * <p>用于 AOP 切面这类「跨端通用」的组件：它们既要服务管理端接口
 * （{@code /admin/**}），也要服务小程序端接口（{@code /app/**}），
 * 因此不能写死用 {@link StpAdminUtil} 或 {@link StpAppUtil}。
 *
 * <p><b>为什么按 URL 前缀判断端，而不是依次尝试两个 StpLogic？</b>
 * 因为「先试 admin 再试 app」每次都要多一次 Redis 查询（查不到就是一次空查），
 * 而幂等切面是高频调用。本项目的路由约定是硬性的：
 * 管理端一律 {@code /admin/**}、小程序端一律 {@code /app/**}，
 * 由 {@code SaTokenConfig} 的拦截器保证。按前缀判断零成本且结果确定。
 *
 * <p>若将来路由约定改变，只需修改本类，不必改动所有切面。
 */
public final class CurrentUserUtils {

    private CurrentUserUtils() {
    }

    /** 未登录时的占位标识，用于幂等 Key 生成（匿名请求也要能各自独立限流） */
    public static final String ANONYMOUS = "anon";

    /**
     * 返回形如 {@code admin:1} 或 {@code app:5} 的登录标识。
     *
     * <p>带上端前缀是必要的：{@code sys_user.id = 1} 与 {@code app_user.id = 1}
     * 是两个毫不相干的人，若只用数字 ID 生成幂等 Key，二者会互相干扰。
     */
    public static String getLoginKey() {
        String uri = ServletUtils.getRequestUri();
        if (uri == null) {
            return ANONYMOUS;
        }
        if (uri.startsWith("/admin")) {
            Long id = StpAdminUtil.getLoginIdDefaultNull();
            return id == null ? ANONYMOUS : StpAdminUtil.TYPE_PREFIX + id;
        }
        if (uri.startsWith("/app")) {
            Long id = StpAppUtil.getLoginIdDefaultNull();
            return id == null ? ANONYMOUS : StpAppUtil.TYPE_PREFIX + id;
        }
        return ANONYMOUS;
    }

    /** 当前登录人的用户类型，对应 {@code order_flow.operator_type} */
    public static int getOperatorType() {
        String uri = ServletUtils.getRequestUri();
        if (uri != null) {
            if (uri.startsWith("/admin")) {
                return CommonConstants.OPERATOR_TYPE_ADMIN;
            }
            if (uri.startsWith("/app")) {
                return CommonConstants.OPERATOR_TYPE_USER;
            }
        }
        // 定时任务、MQ 消费等无请求上下文的场景归为「系统」
        return CommonConstants.OPERATOR_TYPE_SYSTEM;
    }

    /** 当前登录人 ID，无登录态时返回 null */
    public static Long getLoginIdOrNull() {
        String uri = ServletUtils.getRequestUri();
        if (uri == null) {
            return null;
        }
        if (uri.startsWith("/admin")) {
            return StpAdminUtil.getLoginIdDefaultNull();
        }
        if (uri.startsWith("/app")) {
            return StpAppUtil.getLoginIdDefaultNull();
        }
        return null;
    }

    /**
     * 当前登录人的显示名，用于操作日志。
     *
     * <p>优先返回登录时写入会话的显示名（管理员为账号名，小程序用户为昵称），
     * 使日志里出现的是 {@code admin} 而不是 {@code admin:1}。
     * 未登录或会话中没存显示名时，退化为带端前缀的标识（至少能唯一定位到人）。
     */
    public static String getLoginName() {
        String uri = ServletUtils.getRequestUri();
        if (uri == null) {
            return ANONYMOUS;
        }
        String displayName = null;
        if (uri.startsWith("/admin")) {
            displayName = StpAdminUtil.getDisplayName();
        } else if (uri.startsWith("/app")) {
            displayName = StpAppUtil.getDisplayName();
        }
        return displayName != null ? displayName : getLoginKey();
    }
}
