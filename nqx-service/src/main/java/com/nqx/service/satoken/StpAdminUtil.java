package com.nqx.service.satoken;

import cn.dev33.satoken.SaManager;

import java.util.List;

/**
 * 管理端认证工具类。
 *
 * <p>这是 Sa-Token 官方推荐的「同端多账号体系」写法：用一个静态工具类包住
 * {@link StpAdminLogic} 实例，业务代码只调用本类的静态方法，
 * 从而彻底避免误用默认的 {@code StpUtil}（那会读到小程序端的登录态）。
 *
 * <p><b>设计取舍</b>：Sa-Token 自身提供 {@code StpUtil} 静态门面，但静态方法
 * 无法按端区分，因此在多账号体系下必须自建门面。虽然牺牲了一点类型优雅，
 * 但换来了「不可能调错端」的编译期保障 —— 这比少写几行代码重要得多。
 */
public final class StpAdminUtil {

    /**
     * 端标识前缀。
     *
     * <p>用于生成幂等 Key、写操作日志时区分「管理端 1 号用户」和「小程序端 1 号用户」——
     * 两者的 ID 空间是重叠的，不加前缀会互相干扰。
     */
    public static final String TYPE_PREFIX = StpAdminLogic.TYPE + ":";

    /**
     * Account-Session 中存放「显示名」的键。
     *
     * <p>操作日志需要记录可读的操作人名称（如 {@code admin}、{@code 张三}），
     * 而不是 {@code admin:1} 这种标识。登录时把显示名写进 Account-Session，
     * 日志切面直接读会话即可，无需在每个请求里回查数据库。
     * Account-Session 存在 Redis 中，与登录态同生命周期，注销时自动清理。
     */
    public static final String SESSION_KEY_DISPLAY_NAME = "nqx:session:displayName";

    private StpAdminUtil() {
    }

    // ==================== 会话属性 ====================

    /** 登录时写入显示名，供操作日志等场景使用 */
    public static void setDisplayName(Object loginId, String displayName) {
        stpLogic.getSessionByLoginId(loginId).set(SESSION_KEY_DISPLAY_NAME, displayName);
    }

    /** 读取显示名，未登录或未设置时返回 null */
    public static String getDisplayName() {
        Long loginId = getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        Object value = stpLogic.getSessionByLoginId(loginId).get(SESSION_KEY_DISPLAY_NAME);
        return value == null ? null : value.toString();
    }

    /**
     * 管理端 StpLogic 单例。
     *
     * <p>必须由 {@code SaTokenLogicConfig} 调用 {@link SaManager#putStpLogic} 注册，
     * 否则 Sa-Token 的注解鉴权（{@code @SaCheckRole}/{@code @SaCheckPermission}）
     * 无法通过 {@code type = "admin"} 找到本实例。
     */
    public static final StpAdminLogic stpLogic = new StpAdminLogic();

    // ==================== 登录与注销 ====================

    /** 登录，使用全局默认有效期 */
    public static void login(Object loginId) {
        stpLogic.login(loginId);
    }

    /** 登录，并指定本次登录的有效期（秒） */
    public static void login(Object loginId, long timeout) {
        stpLogic.login(loginId, timeout);
    }

    /** 当前会话注销 */
    public static void logout() {
        stpLogic.logout();
    }

    /** 注销指定账号的全部会话（可用于「强制下线」或「禁用账号时踢人」） */
    public static void logout(Object loginId) {
        stpLogic.logout(loginId);
    }

    // ==================== 登录状态 ====================

    public static boolean isLogin() {
        return stpLogic.isLogin();
    }

    public static boolean isLogin(Object loginId) {
        return stpLogic.isLogin(loginId);
    }

    /**
     * 校验当前会话已登录，未登录抛 {@code NotLoginException}。
     * 该异常由 {@code SaTokenExceptionHandler} 统一转换为 401 响应。
     */
    public static void checkLogin() {
        stpLogic.checkLogin();
    }

    // ==================== 身份信息 ====================

    /** 当前登录的管理员 ID，未登录时抛异常 */
    public static Long getLoginIdAsLong() {
        return stpLogic.getLoginIdAsLong();
    }

    /** 当前登录的管理员 ID，未登录时返回 null（用于可选登录的场景） */
    public static Long getLoginIdDefaultNull() {
        Object id = stpLogic.getLoginIdDefaultNull();
        return id == null ? null : Long.valueOf(id.toString());
    }

    public static String getTokenValue() {
        return stpLogic.getTokenValue();
    }

    // ==================== 角色与权限 ====================

    /** 当前管理员的角色标识集合，如 {@code [SUPER_ADMIN]} */
    public static List<String> getRoleList() {
        return stpLogic.getRoleList();
    }

    /** 当前管理员的权限码集合，如 {@code [order:recycle:audit, ...]} */
    public static List<String> getPermissionList() {
        return stpLogic.getPermissionList();
    }

    public static boolean hasRole(String role) {
        return stpLogic.hasRole(role);
    }

    public static boolean hasPermission(String permission) {
        return stpLogic.hasPermission(permission);
    }
}
