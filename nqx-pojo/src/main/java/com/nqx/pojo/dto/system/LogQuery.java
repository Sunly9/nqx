package com.nqx.pojo.dto.system;

import com.nqx.common.constant.CommonConstants;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 日志分页检索条件（操作日志与登录日志共用）。
 *
 * <p><b>为什么两张日志表共用一个查询对象？</b>
 * 它们的筛选维度是同一套问题：「谁、在什么时间、成功还是失败」。
 * 操作日志多一个「模块」维度，登录日志用不到该字段而已，
 * 为此拆成两个类只会带来重复。
 *
 * <p><b>时间范围为什么用 {@link LocalDate} 而不是 {@code LocalDateTime}？</b>
 * 项目没有注册全局的日期转换器，{@code LocalDateTime} 作为 query 参数
 * 需要前端严格传 ISO 的 {@code 2026-10-16T09:30:00}，很容易踩坑；
 * 而 {@code LocalDate} 由 Spring 默认支持 {@code 2026-10-16} 这种写法。
 * 日志排查的实际粒度也是「哪一天出的问题」，日级足够。
 * 区间语义为闭区间（截止日包含当天全天），由 Service 换算成
 * {@code >= 起始日 00:00:00} 与 {@code < 截止日+1 天 00:00:00}。
 */
@Data
public class LogQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务模块，模糊匹配。
     *
     * <p>仅操作日志使用。这里刻意用模糊匹配而不是等值：模块名来自
     * {@code @Log(module = "...")} 注解里手写的字符串，没有字典表约束，
     * 用 like 能让「订单」命中「订单管理」「订单审核」等多个模块。
     */
    private String module;

    /** 操作人 / 登录账号，模糊匹配 */
    private String userName;

    /** 状态 0失败 1成功 */
    private Integer status;

    /** 起始日期（含），格式 yyyy-MM-dd */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 截止日期（含），格式 yyyy-MM-dd */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private Long pageNum;

    private Long pageSize;

    public long safePageNum() {
        return (pageNum == null || pageNum < 1) ? CommonConstants.PAGE_NUM_DEFAULT : pageNum;
    }

    public long safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return CommonConstants.PAGE_SIZE_DEFAULT;
        }
        return Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX);
    }
}
