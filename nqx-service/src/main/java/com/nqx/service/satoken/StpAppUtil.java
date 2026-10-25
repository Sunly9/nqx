package com.nqx.service.satoken;

import cn.dev33.satoken.SaManager;

/**
 * 小程序端认证工具类。
 *
 * <p>与管理端 {@link StpAdminUtil} 对称，但刻意不暴露角色/权限相关方法 ——
 * 因为小程序端没有 RBAC 模型。这是一个刻意的「接口收窄」：
 * 让不该用的能力在代码层面就不可达，比靠文档约定可靠得多。
 */
public final class StpAppUtil {

    /** 端标识前缀，含义见 {@link StpAdminUtil#TYPE_PREFIX} */
    public static final String TYPE_PREFIX = StpAppLogic.TYPE + ":";

    private StpAppUtil() {
    }

    // ==================== 会话属性 ====================

    /** 登录时写入显示名（小程序用户用昵称），供操作日志等场景使用 */
    public static void setDisplayName(Object loginId, String displayName) {
        stpLogic.getSessionByLoginId(loginId).set(StpAdminUtil.SESSION_KEY_DISPLAY_NAME, displayName);
    }

    /** 读取显示名，未登录或未设置时返回 null */
    public static String getDisplayName() {
        Long loginId = getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        Object value = stpLogic.getSessionByLoginId(loginId).get(StpAdminUtil.SESSION_KEY_DISPLAY_NAME);
        return value == null ? null : value.toString();
    }

    /** 小程序端 StpLogic 单例，由 {@code SaTokenLogicConfig} 注册 */
    public static final StpAppLogic stpLogic = new StpAppLogic();

    // ==================== 登录与注销 ====================

    public static void login(Object loginId) {
        stpLogic.login(loginId);
    }

    public static void login(Object loginId, long timeout) {
        stpLogic.login(loginId, timeout);
    }

    public static void logout() {
        stpLogic.logout();
    }

    public static void logout(Object loginId) {
        stpLogic.logout(loginId);
    }

    // ==================== 登录状态 ====================

    public static boolean isLogin() {
        return stpLogic.isLogin();
    }

    public static void checkLogin() {
        stpLogic.checkLogin();
    }

    // ==================== 身份信息 ====================

    /** 当前登录的小程序用户 ID（对应 app_user.id） */
    public static Long getLoginIdAsLong() {
        return stpLogic.getLoginIdAsLong();
    }

    public static Long getLoginIdDefaultNull() {
        Object id = stpLogic.getLoginIdDefaultNull();
        return id == null ? null : Long.valueOf(id.toString());
    }

    public static String getTokenValue() {
        return stpLogic.getTokenValue();
    }
}
