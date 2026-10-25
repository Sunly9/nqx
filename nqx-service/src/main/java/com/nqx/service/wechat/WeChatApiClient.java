package com.nqx.service.wechat;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 微信开放接口客户端。
 *
 * <h2>为什么要有「模拟 openid」这条分支？</h2>
 *
 * <p>调用 {@code jscode2session} 需要真实的 AppID 与 AppSecret，
 * 而且 {@code code} 只能用一次、5 分钟过期。这让本地开发与自动化测试几乎无法进行：
 * 每次都要真机登录、手动复制 code。
 *
 * <p>因此提供一个开关 {@code nqx.wechat.mock-enabled}：
 * 打开时用 {@code code} 的哈希值当作 openid（<b>确定性</b>的，
 * 同一个 code 永远映射到同一个用户，便于反复调试同一账号）。
 *
 * <h2>⚠️ 这个开关绝不能在生产环境打开</h2>
 *
 * <p>它等于允许任何人<b>凭空捏造一个登录身份</b> —— 不需要微信授权，
 * 随便传个字符串就能注册并登录。因此：
 * <ul>
 *   <li>默认值为 {@code false}；</li>
 *   <li>只在 {@code application-dev.yml} 里显式打开；</li>
 *   <li>未配置 AppID/AppSecret 且开关关闭时，直接拒绝登录而不是"放行"。</li>
 * </ul>
 *
 * <p><b>「失败时默认拒绝」是安全设计的基本原则</b>：宁可开发不方便，
 * 也不能因为忘配一个参数就让线上出现人人可登录的漏洞。
 */
@Slf4j
@Component
public class WeChatApiClient {

    @Value("${nqx.wechat.app-id:}")
    private String appId;

    @Value("${nqx.wechat.app-secret:}")
    private String appSecret;

    @Value("${nqx.wechat.login-url:https://api.weixin.qq.com/sns/jscode2session}")
    private String loginUrl;

    @Value("${nqx.wechat.mock-enabled:false}")
    private boolean mockEnabled;

    /** 调用微信接口的超时时间（毫秒）。必须设置，否则微信侧偶发慢响应会占满 Tomcat 线程 */
    private static final int HTTP_TIMEOUT_MILLIS = 5000;

    /** 模拟 openid 的前缀，一眼能看出这条数据来自开发环境，生产库巡检时不会误判 */
    private static final String MOCK_OPENID_PREFIX = "dev_";

    /** 调用方在开发模式下指定模拟身份的前缀，例如 code = "dev:alice" */
    private static final String MOCK_CODE_PREFIX = "dev:";

    /**
     * 用 wx.login 的 code 换取 openid。
     *
     * @param code 前端 wx.login 拿到的临时凭证
     * @return 用户的 openid
     */
    public String getOpenid(String code) {
        boolean configured = StringUtils.hasText(appId) && StringUtils.hasText(appSecret);

        if (!configured) {
            if (!mockEnabled) {
                // 明确拒绝，而不是给一个假的 openid 放行
                log.error("未配置微信 AppID/AppSecret，且未开启模拟登录，无法完成登录");
                throw new BizException(ResultCode.WX_LOGIN_FAILED, "服务端未配置微信登录参数");
            }
            String mockOpenid = mockOpenid(code);
            log.warn("【开发模式】未配置微信参数，使用模拟 openid 登录 | openid={} | 生产环境必须在 application-prod.yml 中保持 mock-enabled=false",
                    mockOpenid);
            return mockOpenid;
        }

        String requestUrl = loginUrl
                + "?appid=" + appId
                + "&secret=" + appSecret
                + "&js_code=" + code
                + "&grant_type=authorization_code";

        String response;
        try {
            response = HttpUtil.get(requestUrl, HTTP_TIMEOUT_MILLIS);
        } catch (Exception e) {
            // 网络异常不能把异常细节暴露给前端，但日志要完整
            log.error("调用微信 jscode2session 失败", e);
            throw new BizException(ResultCode.WX_LOGIN_FAILED);
        }

        JSONObject json = JSONUtil.parseObj(response);
        // 用 Map 风格的 get 并 toString，不依赖 Hutool 具体的 getStr 重载签名
        String openid = asString(json.get("openid"));
        if (!StringUtils.hasText(openid)) {
            String errcode = asString(json.get("errcode"));
            String errmsg = asString(json.get("errmsg"));
            log.warn("微信登录失败 | errcode={} | errmsg={}", errcode, errmsg);
            throw new BizException(ResultCode.WX_LOGIN_FAILED,
                    "微信登录失败（" + (errmsg == null ? "未知原因" : errmsg) + "）");
        }
        return openid;
    }

    /**
     * 生成模拟 openid。
     *
     * <h3>这里有一个必须避开的坑：为什么不能用 code 的哈希？</h3>
     *
     * <p>最初的设计是 {@code openid = md5(code)}。但 {@code wx.login} <b>每次都会返回
     * 一个全新的 code</b>，于是每次登录都会算出一个不同的 openid，
     * 结果是<b>每次登录都创建一个新用户</b>，本地数据库很快就堆满垃圾账号。
     *
     * <p>更糟的是它会让「新用户补资料」的两步流程永远走不完：
     * 第一步用 code1 算出 openid1（用户不存在 → 要求补资料），
     * 第二步用 code2 算出 openid2（又不存在 → 又要求补资料），陷入死循环。
     *
     * <h3>正确的做法：开发模式用固定身份</h3>
     *
     * <p>默认所有开发登录都映射到<b>同一个</b> openid。
     * 这看起来限制很大，其实恰好合适 —— 本项目的核心设计就是
     * 「一个用户可以同时是回收方和采购方」（替代旧版 users / procurement_users
     * 两张表），所以用一个账号测试两种身份是完全够用的。
     *
     * <p>若确实需要模拟多个用户，让调用方传 {@code code = "dev:alice"} 这样的形式，
     * {@code dev:} 之后的部分作为身份种子。这比要求开发者去改代码方便得多。
     */
    private String mockOpenid(String code) {
        String seed = (code != null && code.startsWith(MOCK_CODE_PREFIX))
                ? code.substring(MOCK_CODE_PREFIX.length())
                : "default";
        return MOCK_OPENID_PREFIX + md5Hex(seed).substring(0, 12);
    }

    /** 计算十六进制 MD5。用于把身份种子稳定地映射成一个固定长度的字符串 */
    private String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必须支持的算法，走到这里说明运行环境异常
            throw new IllegalStateException("当前运行环境不支持 MD5", e);
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
