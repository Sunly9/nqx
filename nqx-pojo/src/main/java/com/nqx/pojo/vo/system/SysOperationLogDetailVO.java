package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysOperationLog;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志详情（含入参与响应，仅详情接口使用）。
 *
 * <p><b>为什么宁可多一个类也不直接返回 {@code SysOperationLog} 实体？</b>
 * 项目在 {@code nqx-pojo} 的模块说明里定了一条硬规则：实体不出 Service 层。
 * 操作日志实体目前确实没有需要隐藏的字段（写入前已脱敏），
 * 但「现在没有」不等于「以后没有」—— 一旦有人给日志表加上
 * 原始 UA、token 指纹、未脱敏的请求体快照，直接返回实体的接口就会
 * 在没有任何人改动接口代码的情况下开始泄露数据。
 * 让 VO 成为唯一的出口，泄露就必须经过一次显式的代码修改。
 */
@Data
public class SysOperationLogDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String traceId;

    private Long userId;

    /** 操作人类型 1小程序用户 2管理员 3系统 */
    private Integer userType;

    private String userName;

    private String module;

    private String operation;

    /** 目标方法（全限定类名 + 方法名），排查"到底执行了哪段代码"时最有用 */
    private String method;

    private String requestUrl;

    private String requestMethod;

    /** 请求参数（JSON，已脱敏、已截断到 4000 字符） */
    private String requestParam;

    /** 响应结果（JSON，已截断；仅当接口显式开启 {@code saveResult} 时才有值） */
    private String responseResult;

    private String ip;

    private String location;

    /** 状态 0失败 1成功 */
    private Integer status;

    private String errorMsg;

    private Long costTime;

    private LocalDateTime createTime;

    public static SysOperationLogDetailVO from(SysOperationLog log) {
        SysOperationLogDetailVO vo = new SysOperationLogDetailVO();
        vo.setId(log.getId());
        vo.setTraceId(log.getTraceId());
        vo.setUserId(log.getUserId());
        vo.setUserType(log.getUserType());
        vo.setUserName(log.getUserName());
        vo.setModule(log.getModule());
        vo.setOperation(log.getOperation());
        vo.setMethod(log.getMethod());
        vo.setRequestUrl(log.getRequestUrl());
        vo.setRequestMethod(log.getRequestMethod());
        vo.setRequestParam(log.getRequestParam());
        vo.setResponseResult(log.getResponseResult());
        vo.setIp(log.getIp());
        vo.setLocation(log.getLocation());
        vo.setStatus(log.getStatus());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setCostTime(log.getCostTime());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
