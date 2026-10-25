package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小程序登录返回。
 *
 * <p>结构与旧版前端的期望保持一致：
 * <pre>{@code
 * const { token, userInfo, status } = res.data;
 * if (status === 'new_user') { // 跳资料填写
 * } else if (token && userInfo) { // 登录成功
 * }
 * }</pre>
 *
 * <p>这里的 {@code status} 是一个「流程状态」而不是 HTTP 状态，
 * 用于表达"登录流程走到哪一步了"。用 200 状态码 + 业务字段表达流程分支，
 * 比用不同 HTTP 状态码（如 200 表示老用户、201 表示新用户）更易读，
 * 也不会被各种网络中间件改写。
 */
@Data
public class AppLoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 需要先补全资料（新用户第一次登录） */
    public static final String STATUS_NEW_USER = "new_user";

    /** 已存在用户，直接登录成功 */
    public static final String STATUS_EXISTING_USER = "existing_user";

    /**
     * 登录流程状态。
     * 新用户首次登录时返回 {@code "new_user"}，前端据此显示资料填写表单，
     * 此时 {@code token} 与 {@code userInfo} 均为 {@code null}。
     */
    private String status;

    /** 访问令牌，前端存 Storage 并在后续请求的 Authorization 头中携带 */
    private String token;

    /** 令牌有效期（秒） */
    private Long expiresIn;

    /** 用户信息。字段名保持为 {@code userInfo} 以匹配前端取值代码 */
    @JsonProperty("userInfo")
    private AppUserVO userInfo;

    /** 新用户首次登录：只告知前端"需要补资料"，不下发 token */
    public static AppLoginVO needProfile() {
        AppLoginVO vo = new AppLoginVO();
        vo.setStatus(STATUS_NEW_USER);
        return vo;
    }

    public static AppLoginVO of(String token, Long expiresIn, AppUserVO userInfo) {
        AppLoginVO vo = new AppLoginVO();
        vo.setStatus(STATUS_EXISTING_USER);
        vo.setToken(token);
        vo.setExpiresIn(expiresIn);
        vo.setUserInfo(userInfo);
        return vo;
    }
}
