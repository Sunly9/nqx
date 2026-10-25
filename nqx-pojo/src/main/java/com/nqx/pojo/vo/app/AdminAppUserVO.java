package com.nqx.pojo.vo.app;

import com.nqx.pojo.entity.AppUser;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端 - 小程序用户列表项。
 *
 * <h2>为什么不复用 {@code AppUserVO}</h2>
 *
 * <p>{@link AppUserVO} 是**小程序自己的 userInfo 契约**：它的字段名被刻意
 * 固定成下划线（{@code avatar_url}、{@code total_weight}），因为旧页面就是这么读的。
 * 那个类服务于"用户看自己"，不该为了管理端的需求去改它 ——
 * 一旦改动，小程序端所有读 userInfo 的地方都可能静默失效。
 *
 * <p>两个场景的需求本来也不同：
 * <ul>
 *   <li>小程序：需要 {@code roles}（判断有没有采购/回收身份）、不需要登录时间；</li>
 *   <li>管理端：需要 {@code last_login_time}、{@code create_time}（活跃度与新增分析）、
 *       不需要 {@code roles}（那是用户自己的事）。</li>
 * </ul>
 *
 * <p><b>用两个 VO 表达两种视图，比用一个 VO 加一堆可选字段更诚实。</b>
 *
 * <h2>为什么不直接返回实体</h2>
 *
 * <p>实体里有 {@code openid}、{@code unionid}、{@code deleted}。
 * openid 是微信用户的唯一标识，泄露它等于知道了"这个人在微信里的身份"，
 * 不该出现在后台列表里。而 {@code deleted} 是逻辑删除标记，暴露出去没有意义。
 *
 * <p>显式列出要暴露的字段，能让"以后实体新增字段"默认**不**泄露 ——
 * 如果直接返回实体，加一个"身份证号"字段就会自动出现在接口上，
 * 而没人会记得回头检查这件事。
 */
@Data
public class AdminAppUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    /** 头像地址（管理端用驼峰即可，不承接旧页面契约） */
    private String avatarUrl;

    private String phone;

    private String realName;

    private String studentNo;

    private String college;

    private String labName;

    /** 累计回收重量（kg） */
    private BigDecimal totalWeight;

    /** 累计碳减排（kg） */
    private BigDecimal carbonSaved;

    /** 状态 0禁用 1正常 */
    private Integer status;

    /** 当前积分余额（points_account.balance），由 Service 批量回填 */
    private Integer points;

    /*
     * 注意：本 VO 的字段名**统一用驼峰**，不加 @JsonProperty。
     *
     * 与同目录的 AppUserVO 不同 —— 那个类必须输出下划线，因为旧小程序的页面
     * 就是按 `avatar_url` / `total_weight` 读的，改了就静默失效。
     *
     * 而本类是新加的管理端接口，没有任何历史契约要遵守。
     * 既然可以自由选，就选与其它管理端 VO（如 SysUserVO 的 lastLoginTime）
     * 一致的驼峰 —— 同一个后台里两种命名风格混用，是最没必要的阅读成本。
     *
     * （第一版我给这两个字段加了 @JsonProperty 写死下划线，结果这个 VO 里
     *   一部分驼峰、一部分下划线，自己就成了"混用"的反例。已改回统一驼峰。）
     */
    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;

    /**
     * 由实体构建（**不含积分**）。
     *
     * <p>积分在 {@code points_account} 表里，由 Service 一次 IN 查完后回填。
     * 不让本方法自己去查库：VO 一旦依赖 Mapper 就变成"离开数据库构造不出来"，
     * 既难单测，也会把 N+1 的风险藏进看起来无害的转换方法里。
     */
    public static AdminAppUserVO from(AppUser user) {
        AdminAppUserVO vo = new AdminAppUserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhone(user.getPhone());
        vo.setRealName(user.getRealName());
        vo.setStudentNo(user.getStudentNo());
        vo.setCollege(user.getCollege());
        vo.setLabName(user.getLabName());
        vo.setTotalWeight(user.getTotalWeight());
        vo.setCarbonSaved(user.getCarbonSaved());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
