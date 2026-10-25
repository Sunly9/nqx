package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户信息接口的返回包装。
 *
 * <p><b>为什么要多包一层 {@code userInfo}？</b>
 * 因为前端读的是 {@code res.data.userInfo}。
 * 由于请求垫片已经把后端统一返回体 {@code Result} 解包到 {@code res.data}，
 * 若这里直接返回 {@code AppUserVO}，前端拿到的 {@code res.data} 就是用户对象本身，
 * {@code res.data.userInfo} 会是 {@code undefined}。
 *
 * <p>这是典型的「多包一层少改一堆页面」的取舍：
 * 后端多一个 20 行的类，前端 27 个页面里所有 {@code res.data.userInfo} 都不用动。
 */
@Data
public class AppUserInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonProperty("userInfo")
    private AppUserVO userInfo;

    public static AppUserInfoVO of(AppUserVO userInfo) {
        AppUserInfoVO vo = new AppUserInfoVO();
        vo.setUserInfo(userInfo);
        return vo;
    }
}
