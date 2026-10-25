package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 小程序用户信息（返回给前端的 userInfo）。
 *
 * <h2>字段名为什么必须用 @JsonProperty 写成下划线？</h2>
 *
 * <p>旧版 Node 后端是把 MySQL 查询结果**整行原样返回**的，
 * 所以前端的取值代码写的是数据库列名：
 *
 * <pre>{@code
 * const userInfo = res.data.userInfo;
 * userInfo.avatar_url      // ← 下划线
 * userInfo.total_weight    // ← 下划线
 * userInfo.carbon_saved    // ← 下划线
 * userInfo.points          // ← 无下划线
 * userInfo.nickname        // ← 全小写，不是 nickName
 * }</pre>
 *
 * <p>若后端改成驼峰（{@code avatarUrl}、{@code totalWeight}），
 * 前端读到的全是 {@code undefined}，而且**不会报任何错**——
 * 页面只是安静地少显示几项数据。这类问题极难定位。
 *
 * <p>解法不是把 Java 字段也写成下划线（那会违反 Java 命名规范、
 * 且每处使用都要忍受），而是用 Jackson 的 {@link JsonProperty}
 * <b>让 Java 侧保持驼峰、JSON 侧输出下划线</b>。
 * 这正是序列化注解存在的意义：把「语言内命名」与「对外契约」解耦。
 */
@Data
public class AppUserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 昵称。
     *
     * <p>注意是 {@code nickname} 全小写 —— 与数据库列名一致。
     * 而登录入参里微信传上来的是 {@code nickName}（大写 N），两者不同，不要混淆。
     */
    private String nickname;

    /** 头像地址。前端读的是 {@code avatar_url} */
    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String phone;

    /** 真实姓名 */
    private String realName;

    /** 学号 / 工号 */
    private String studentNo;

    /** 学院 */
    private String college;

    /** 实验室名称 */
    private String labName;

    /** 当前可用积分（来自 points_account.balance） */
    private Integer points;

    /** 累计回收重量（kg）。前端读 {@code total_weight} */
    @JsonProperty("total_weight")
    private BigDecimal totalWeight;

    /** 累计碳减排量（kg）。前端读 {@code carbon_saved} */
    @JsonProperty("carbon_saved")
    private BigDecimal carbonSaved;

    /** 状态 0禁用 1正常 */
    private Integer status;

    /**
     * 用户身份列表：1回收方 2采购方。
     *
     * <p>新设计里一个用户可以同时拥有两种身份（替换掉旧版
     * users / procurement_users 两张表的设计），因此这里是数组而非单值。
     */
    private List<Integer> roles;
}
