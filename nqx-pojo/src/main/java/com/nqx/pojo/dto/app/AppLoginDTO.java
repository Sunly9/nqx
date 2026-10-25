package com.nqx.pojo.dto.app;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 小程序登录入参。
 *
 * <p><b>字段名为什么是 {@code code} + {@code role} + {@code userInfo}？</b>
 * 因为它们直接对应旧版小程序前端的请求体。保持字段名一致可以让
 * 27 个页面里已有的登录调用**一行都不用改**，只需把请求发到新的地址。
 * 接口契约由现有前端决定，这不是妥协，而是避免无谓的改造量。
 *
 * <p>登录分两步（与前端交互流程一致）：
 * <ol>
 *   <li>第一次只带 {@code code}（前端还会带 {@code checkUser: true}，后端忽略即可）——
 *       若用户不存在，返回 {@code status = "new_user"}，前端弹出资料填写表单；</li>
 *   <li>第二次带 {@code code} + {@code userInfo}（昵称、头像）—— 完成注册并登录。</li>
 * </ol>
 *
 * <p>微信自 2022 起废弃 {@code wx.getUserProfile}，昵称与头像必须由用户
 * 通过 {@code <button open-type="chooseAvatar">} 和 {@code <input type="nickname">}
 * 主动提供，这正是前端 login 页面的做法。
 */
@Data
public class AppLoginDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** wx.login 拿到的临时登录凭证，用于换取 openid */
    @NotBlank(message = "缺少微信登录凭证")
    private String code;

    /**
     * 用户选择的身份。
     * <ul>
     *   <li>{@code lab} —— 回收方</li>
     *   <li>{@code procurement} —— 采购方</li>
     * </ul>
     * 旧版用这个值区分调不同的登录接口；新版只用一个接口，该值用于给用户绑定身份。
     */
    private String role;

    /** 用户主动提供的昵称与头像，第一次登录时为空（前端先问后端"是不是新用户"） */
    private WxUserInfo userInfo;

    /**
     * 微信用户信息。
     *
     * <p>内部类字段名保持 {@code nickName}（大写 N）——这是微信小程序端
     * {@code chooseAvatar} / {@code nickname} 事件回调里的原始字段名，
     * 前端会原样透传上来。
     */
    @Data
    public static class WxUserInfo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 用户输入的昵称 */
        private String nickName;

        /** 用户选择的头像临时路径 */
        private String avatarUrl;
    }
}
