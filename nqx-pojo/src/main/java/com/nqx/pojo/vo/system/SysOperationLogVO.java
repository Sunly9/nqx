package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysOperationLog;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志列表项（<b>刻意不含 requestParam / responseResult</b>）。
 *
 * <p><b>为什么列表接口必须把这两个字段裁掉？</b>
 * 写入时它们被截断到 4000 字符（见 {@code LogAspect}），也就是说单行日志
 * 最坏情况下仅这两个字段就有 8KB。一页 20 条的列表接口因此可能返回 160KB，
 * 而其中 99% 的内容在表格里根本不会显示 —— 用户要的是「谁在什么时候做了什么」。
 * 大字段只在详情接口按需拉取，是列表接口的通用优化手段，
 * 与商品列表只返回封面图、详情才返回富文本是同一个道理。
 */
@Data
public class SysOperationLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 链路追踪 ID，可凭它在日志文件里定位该次请求的完整调用链 */
    private String traceId;

    private Long userId;

    /** 操作人类型 1小程序用户 2管理员 3系统 */
    private Integer userType;

    private String userName;

    /** 业务模块 */
    private String module;

    /** 操作描述 */
    private String operation;

    private String method;

    private String requestUrl;

    private String requestMethod;

    private String ip;

    private String location;

    /** 状态 0失败 1成功 */
    private Integer status;

    /** 异常信息（写入时已截断到 1000 字符，可安全随列表返回） */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costTime;

    private LocalDateTime createTime;

    public static SysOperationLogVO from(SysOperationLog log) {
        SysOperationLogVO vo = new SysOperationLogVO();
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
        vo.setIp(log.getIp());
        vo.setLocation(log.getLocation());
        vo.setStatus(log.getStatus());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setCostTime(log.getCostTime());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
