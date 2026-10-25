package com.nqx.service.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.AppUserMapper;
import com.nqx.mapper.PointsAccountMapper;
import com.nqx.pojo.dto.app.AppUserQuery;
import com.nqx.pojo.entity.AppUser;
import com.nqx.pojo.entity.PointsAccount;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.AdminAppUserVO;
import com.nqx.service.satoken.StpAppUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端 - 小程序用户管理。
 *
 * <h2>为什么现在才有这个模块</h2>
 *
 * <p>后台原来能管商品、订单、积分、题库、管理员，唯独**看不到小程序用户**。
 * 这会带来几个很实际的问题：
 *
 * <ul>
 *   <li>用户反馈"我的订单有问题"，运营没法查他名下的单，只能让他自己截图；</li>
 *   <li>遇到刷单、恶意兑换的账号，**没有任何手段限制**；</li>
 *   <li>"这个月新增多少用户""谁的回收量最高"这类运营问题无从回答。</li>
 * </ul>
 *
 * <p>{@code app_user} 表的数据一直是完整的 —— 缺的只是入口。
 *
 * <h2>为什么禁用要同时踢下线</h2>
 *
 * <p>Sa-Token 的会话是独立的：只把 {@code status} 改成 0 而不管会话，
 * 被禁用的用户**手里的旧 token 仍然有效**，直到自然过期（本项目是 7 天）。
 * 也就是说"禁用"这个动作在 7 天内实际上没生效。
 *
 * <p>所以这里调用 {@link StpAppUtil#logout(Object)} 主动注销。
 * 这个教训在管理端账号那里已经踩过一次（见 {@code SysUserService}），
 * 小程序端同理 —— <b>权限/状态变更必须同时处理已经发出去的凭证。</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserAdminService {

    private final AppUserMapper appUserMapper;
    private final PointsAccountMapper pointsAccountMapper;

    /**
     * 分页查询小程序用户。
     *
     * <p>积分余额来自 {@code points_account} 表，按本页的用户 id **一次 IN 查完**
     * 再回填 —— 而不是每行单独查一次（一页 10 条就是 10 次额外往返）。
     * 这与 {@code AppOrderService#loadItems} 的做法一致。
     */
    public PageResult<AdminAppUserVO> page(AppUserQuery query) {
        Page<AppUser> page = new Page<>(query.safePageNum(), query.safePageSize());

        Page<AppUser> result = appUserMapper.selectPage(page, Wrappers.<AppUser>lambdaQuery()
                .like(hasText(query.getNickname()), AppUser::getNickname, query.getNickname())
                .like(hasText(query.getPhone()), AppUser::getPhone, query.getPhone())
                .like(hasText(query.getCollege()), AppUser::getCollege, query.getCollege())
                .eq(query.getStatus() != null, AppUser::getStatus, query.getStatus())
                // 注册时间区间：起止都含当天（用 >= 与 < 次日，避免 23:59:59.5 漏数据）
                .ge(query.getStartDate() != null, AppUser::getCreateTime,
                        query.getStartDate() == null ? null : query.getStartDate().atStartOfDay())
                .lt(query.getEndDate() != null, AppUser::getCreateTime,
                        query.getEndDate() == null ? null : query.getEndDate().plusDays(1).atStartOfDay())
                .orderByDesc(AppUser::getCreateTime)
                .orderByDesc(AppUser::getId));

        Map<Long, Integer> balanceByUser = loadBalances(result.getRecords());
        return PageUtil.of(result, user -> {
            AdminAppUserVO vo = AdminAppUserVO.from(user);
            // 没有积分账户的用户（理论上注册时会创建，但历史数据可能缺）显示 0
            vo.setPoints(balanceByUser.getOrDefault(user.getId(), 0));
            return vo;
        });
    }

    /**
     * 启用 / 禁用小程序用户。
     *
     * <p>禁用时同时注销该用户的所有会话 —— 否则旧 token 在有效期内仍然可用，
     * 相当于"禁用"没有真正生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != CommonConstants.STATUS_ENABLED
                && status != CommonConstants.STATUS_DISABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "状态值只能是 0（禁用）或 1（启用）");
        }
        AppUser exists = appUserMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);

        if (status.equals(exists.getStatus())) {
            // 幂等：状态没变就直接返回，避免多余的 UPDATE 与会话注销
            return;
        }

        AppUser update = new AppUser();
        update.setId(id);
        update.setStatus(status);
        appUserMapper.updateById(update);

        if (status == CommonConstants.STATUS_DISABLED) {
            // 主动注销：让"禁用"立刻生效，而不是等 token 自然过期
            StpAppUtil.logout(id);
            log.info("禁用小程序用户并注销会话 | userId={}", id);
        } else {
            log.info("启用小程序用户 | userId={}", id);
        }
    }

    /** 批量查积分余额，避免逐行查询造成 N+1 */
    private Map<Long, Integer> loadBalances(List<AppUser> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = users.stream().map(AppUser::getId).toList();
        return pointsAccountMapper.selectList(Wrappers.<PointsAccount>lambdaQuery()
                        .select(PointsAccount::getUserId, PointsAccount::getBalance)
                        .in(PointsAccount::getUserId, ids))
                .stream()
                .filter(a -> a.getBalance() != null)
                .collect(Collectors.toMap(PointsAccount::getUserId, PointsAccount::getBalance, (a, b) -> a));
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
