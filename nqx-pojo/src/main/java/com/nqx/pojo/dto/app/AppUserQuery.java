package com.nqx.pojo.dto.app;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 管理端 - 小程序用户检索条件。
 *
 * <p>所有条件可选，前端传哪个就拼哪个 WHERE（与 {@code SysUserQuery}、
 * {@code ProductQuery} 的约定一致）。
 *
 * <p><b>为什么按「注册时间区间」而不是「最后登录时间」筛选</b>：
 * 运营最常问的是"这个月新进来多少人"，那是注册时间；
 * 而"多久没登录了"是另一个维度（活跃度分析），需要单独的报表口径。
 * 一个查询只服务一个意图，比塞两个维度进去更清楚。
 */
@Data
public class AppUserQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 昵称，模糊匹配 */
    private String nickname;

    /** 手机号，模糊匹配（用户可能只记得后四位） */
    private String phone;

    /** 所属学院，模糊匹配 */
    private String college;

    /** 状态 0禁用 1正常 */
    private Integer status;

    /** 注册时间起（含） */
    private LocalDate startDate;

    /** 注册时间止（含） */
    private LocalDate endDate;

    private Long pageNum;

    private Long pageSize;

    public long safePageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    public long safePageSize() {
        // 上限 100：这个页面是给运营翻人用的，不是导出全量
        long size = pageSize == null || pageSize < 1 ? 10L : pageSize;
        return Math.min(size, 100L);
    }
}
