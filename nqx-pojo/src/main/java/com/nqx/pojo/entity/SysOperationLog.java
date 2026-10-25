package com.nqx.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 链路追踪ID，可用于在日志文件中定位该次请求的完整调用链（V3 迁移新增） */
    private String traceId;

    /** 操作人 ID */
    private Long userId;

    /** 操作人类型 1管理员 2小程序用户 */
    private Integer userType;

    /** 操作人名称 */
    private String userName;

    /** 业务模块 */
    private String module;

    /** 操作描述 */
    @TableField("`operation`")
    private String operation;

    /** 目标方法 */
    private String method;

    /** 请求 URL */
    private String requestUrl;

    /** HTTP 方法 */
    private String requestMethod;

    /** 请求参数（脱敏后） */
    private String requestParam;

    /** 响应结果（截断） */
    private String responseResult;

    /** IP */
    private String ip;

    /** IP 归属地 */
    private String location;

    /** 状态 0失败 1成功 */
    private Integer status;

    /** 异常信息 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long costTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
