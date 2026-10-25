package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 小程序用户
 */
@Data
@TableName("app_user")
public class AppUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 微信 openid（当前小程序唯一标识） */
    private String openid;

    /** 微信 unionid（多应用打通时使用） */
    private String unionid;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** 手机号 */
    private String phone;

    /** 真实姓名 */
    private String realName;

    /** 学号 / 工号 */
    private String studentNo;

    /** 学院 */
    private String college;

    /** 实验室名称 */
    private String labName;

    /** 累计回收重量（kg） */
    private BigDecimal totalWeight;

    /** 累计碳减排量（kg），前端"环保贡献"展示用 */
    private BigDecimal carbonSaved;

    /** 状态 0禁用 1正常 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 注册时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除 0未删 1已删 */
    @TableLogic
    private Integer deleted;
}
