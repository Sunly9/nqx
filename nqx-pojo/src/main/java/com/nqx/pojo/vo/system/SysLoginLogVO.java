package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysLoginLog;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志列表项。
 *
 * <p><b>字段与实体目前一一对应，为什么还要单独建 VO？</b>
 * 因为「目前对应」不是契约。日志表是最容易被后续需求加字段的地方
 * （比如为了排查「异地登录」而加上 token 指纹、原始 UA、失败原因详情），
 * 一旦直接返回实体，这些新列会在无人察觉的情况下自动出现在接口响应里。
 * 保留 VO 这一层，等于给「哪些字段可以出门」留了一个显式的评审点。
 */
@Data
public class SysLoginLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 用户类型 1小程序用户 2管理员 3系统 */
    private Integer userType;

    /** 登录账号 */
    private String userName;

    private String ip;

    /** IP 归属地 */
    private String location;

    private String browser;

    private String os;

    /** 状态 0失败 1成功 */
    private Integer status;

    /** 提示信息，如「登录成功」「账号或密码错误」 */
    private String msg;

    private LocalDateTime loginTime;

    public static SysLoginLogVO from(SysLoginLog log) {
        SysLoginLogVO vo = new SysLoginLogVO();
        vo.setId(log.getId());
        vo.setUserId(log.getUserId());
        vo.setUserType(log.getUserType());
        vo.setUserName(log.getUserName());
        vo.setIp(log.getIp());
        vo.setLocation(log.getLocation());
        vo.setBrowser(log.getBrowser());
        vo.setOs(log.getOs());
        vo.setStatus(log.getStatus());
        vo.setMsg(log.getMsg());
        vo.setLoginTime(log.getLoginTime());
        return vo;
    }
}
