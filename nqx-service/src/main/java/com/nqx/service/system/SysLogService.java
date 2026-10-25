package com.nqx.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.SysLoginLogMapper;
import com.nqx.mapper.SysOperationLogMapper;
import com.nqx.pojo.dto.system.LogQuery;
import com.nqx.pojo.entity.SysLoginLog;
import com.nqx.pojo.entity.SysOperationLog;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.system.SysLoginLogVO;
import com.nqx.pojo.vo.system.SysOperationLogDetailVO;
import com.nqx.pojo.vo.system.SysOperationLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 日志查询服务。
 *
 * <p><b>本类只读，不负责写日志。</b>日志的写入由 {@code LogAspect} +
 * {@code OperationLogRecorder} 在切面里异步完成，登录日志由
 * {@code AdminAuthService#login} 写入。这里做查询这件事本身还有一个好处：
 * 日志表是「只追加」的，因此查询侧不需要考虑并发修改，
 * 唯一需要操心的是<b>别让列表接口把大字段一起拖出来</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysLogService {

    /**
     * 操作日志列表需要排除的字段。
     *
     * <p>{@code LogAspect} 会把入参与响应截断到 4000 字符后落库，
     * 也就是说这两个字段最坏情况下各占 4KB。一页 20 条 = 最多 160KB 的响应体，
     * 而表格里根本不会渲染它们 —— 大字段只在详情接口按需取。
     * 排除动作在 SQL 层完成（不查出来），而不是查出来后置空：
     * 后者省不掉数据库到应用的传输与反序列化开销。
     */
    private static final Set<String> LIST_EXCLUDED_FIELDS = Set.of("requestParam", "responseResult");

    private final SysOperationLogMapper sysOperationLogMapper;
    private final SysLoginLogMapper sysLoginLogMapper;

    /** 操作日志分页 */
    public PageResult<SysOperationLogVO> pageOperations(LogQuery query) {
        Page<SysOperationLog> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<SysOperationLog> wrapper = Wrappers.<SysOperationLog>lambdaQuery()
                .select(SysOperationLog.class,
                        field -> !LIST_EXCLUDED_FIELDS.contains(field.getProperty()))
                .like(StringUtils.hasText(query.getModule()), SysOperationLog::getModule, query.getModule())
                .like(StringUtils.hasText(query.getUserName()), SysOperationLog::getUserName, query.getUserName())
                .eq(query.getStatus() != null, SysOperationLog::getStatus, query.getStatus())
                .ge(query.getStartDate() != null, SysOperationLog::getCreateTime,
                        startOfDay(query.getStartDate()))
                .lt(query.getEndDate() != null, SysOperationLog::getCreateTime,
                        startOfNextDay(query.getEndDate()))
                // 时间倒序是日志的唯一合理顺序；再加 id 倒序是为了让同一秒内写入的多条
                // 也有稳定顺序（create_time 只精确到秒，而一次请求可能产生多条日志）
                .orderByDesc(SysOperationLog::getCreateTime)
                .orderByDesc(SysOperationLog::getId);

        IPage<SysOperationLog> result = sysOperationLogMapper.selectPage(page, wrapper);
        return PageUtil.of(result, SysOperationLogVO::from);
    }

    /** 操作日志详情：这里是唯一会返回 requestParam / responseResult 的接口 */
    public SysOperationLogDetailVO operationDetail(Long id) {
        SysOperationLog logEntity = sysOperationLogMapper.selectById(id);
        if (logEntity == null) {
            throw new BizException(ResultCode.NOT_FOUND, "操作日志不存在或已被清理");
        }
        return SysOperationLogDetailVO.from(logEntity);
    }

    /**
     * 登录日志分页。
     *
     * <p>刻意<b>不按 {@code userType} 过滤</b>，管理端与小程序端的登录记录都会返回。
     * 登录日志的排查场景常常是「某个账号是不是被人试过密码」，
     * 而尝试者可能是小程序端账号、也可能是后台账号 ——
     * 默认只给一半数据，反而会让人误判"没有异常登录"。前端用类型标签区分即可。
     */
    public PageResult<SysLoginLogVO> pageLogins(LogQuery query) {
        Page<SysLoginLog> page = new Page<>(query.safePageNum(), query.safePageSize());
        LambdaQueryWrapper<SysLoginLog> wrapper = Wrappers.<SysLoginLog>lambdaQuery()
                .like(StringUtils.hasText(query.getUserName()), SysLoginLog::getUserName, query.getUserName())
                .eq(query.getStatus() != null, SysLoginLog::getStatus, query.getStatus())
                .ge(query.getStartDate() != null, SysLoginLog::getLoginTime,
                        startOfDay(query.getStartDate()))
                .lt(query.getEndDate() != null, SysLoginLog::getLoginTime,
                        startOfNextDay(query.getEndDate()))
                .orderByDesc(SysLoginLog::getLoginTime)
                .orderByDesc(SysLoginLog::getId);

        IPage<SysLoginLog> result = sysLoginLogMapper.selectPage(page, wrapper);
        return PageUtil.of(result, SysLoginLogVO::from);
    }

    // ==================== 私有方法 ====================

    /** 起始日 00:00:00，配合 {@code >=} 使用 */
    private LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    /**
     * 截止日的次日 00:00:00，配合 {@code <} 使用。
     *
     * <p>这样写而不是 {@code <= 截止日 23:59:59}：后者会漏掉
     * 23:59:59.5 这样的毫秒级数据（DATETIME 列在 MySQL 5.6+ 支持小数秒），
     * 而「截止当天」的语义应该是完整的一天。
     */
    private LocalDateTime startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }
}
