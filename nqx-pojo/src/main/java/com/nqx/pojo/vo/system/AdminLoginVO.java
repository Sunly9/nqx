package com.nqx.pojo.vo.system;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员登录返回。
 */
@Data
public class AdminLoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 访问令牌，前端需在后续请求的 Authorization 头中携带（格式：Bearer {token}） */
    private String token;

    /** 令牌有效期（秒），前端可据此做无感刷新或提前提示重新登录 */
    private Long expiresIn;

    /** 登录用户信息，避免前端登录后立刻再发一次 /info 请求 */
    private AdminInfoVO user;
}
