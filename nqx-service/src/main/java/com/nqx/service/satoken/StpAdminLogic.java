package com.nqx.service.satoken;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理端登录体系。
 *
 * <p><b>为什么需要独立于默认 {@code StpUtil} 的 StpLogic？</b>
 *
 * <p>本项目有两套完全不同的用户体系，且必须同时在线：
 * <table border="1">
 *   <tr><th></th><th>管理端</th><th>小程序端</th></tr>
 *   <tr><td>用户表</td><td>{@code sys_user}</td><td>{@code app_user}</td></tr>
 *   <tr><td>登录凭证</td><td>账号 + 密码</td><td>微信 code 换 openid</td></tr>
 *   <tr><td>权限模型</td><td>完整 RBAC（角色 + 权限码）</td><td>仅身份区分（回收方/采购方）</td></tr>
 * </table>
 *
 * <p>若共用一套 {@code StpUtil}，会出现两个严重问题：
 * <ol>
 *   <li><b>ID 冲突</b>：{@code sys_user.id = 1} 和 {@code app_user.id = 1} 是两个毫不相干的人，
 *       但 Sa-Token 只用 loginId 识别身份，会把管理员的登录态错认成某个小程序用户；</li>
 *   <li><b>越权风险</b>：小程序端拿到的 token 可能被用来访问管理端接口。</li>
 * </ol>
 *
 * <p><b>隔离是如何实现的？</b>
 * Sa-Token 在读写会话时会把 {@code loginType} 拼进 Key 前缀
 * （形如 {@code admin:token:<tokenValue>}、{@code app:token:<tokenValue>}）。
 * 因此即便两个端传的是同一个 token 字符串，在各自的命名空间下也互不可见 ——
 * 一个端的 token 拿到另一个端使用，会被判定为「未登录」。
 *
 * <p>注意：其实例必须通过 {@code SaManager.putStpLogic(...)} 注册，
 * 见 {@code SaTokenLogicConfig}。
 */
public class StpAdminLogic extends StpLogic {

    /** 登录类型标识，同时作为会话 Key 的命名空间前缀 */
    public static final String TYPE = "admin";

    public StpAdminLogic() {
        super(TYPE);
    }
}
