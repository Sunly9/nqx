package com.nqx.service.satoken;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 小程序端登录体系。
 *
 * <p>与管理端 {@link StpAdminLogic} 相互隔离，原理与必要性见该类的说明。
 *
 * <p>与小程序端相比，管理端的差异在于：
 * <ul>
 *   <li>管理端有完整的角色/权限码校验（{@code @SaCheckPermission(type = "admin")}）；</li>
 *   <li>小程序端只需要区分「回收方 / 采购方」身份，
 *       因为业务权限边界基本等同于「本人只能操作本人的数据」。</li>
 * </ul>
 */
public class StpAppLogic extends StpLogic {

    /** 登录类型标识，同时作为会话 Key 的命名空间前缀 */
    public static final String TYPE = "app";

    public StpAppLogic() {
        super(TYPE);
    }
}
