package com.nqx.service.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.AppUserMapper;
import com.nqx.mapper.AppUserRoleMapper;
import com.nqx.mapper.PointsAccountMapper;
import com.nqx.pojo.dto.app.AppLoginDTO;
import com.nqx.pojo.entity.AppUser;
import com.nqx.pojo.entity.AppUserRole;
import com.nqx.pojo.entity.PointsAccount;
import com.nqx.pojo.vo.app.AppUserVO;
import com.nqx.pojo.vo.app.AppLoginVO;
import com.nqx.service.points.PointsService;
import com.nqx.service.satoken.StpAppUtil;
import com.nqx.service.wechat.WeChatApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 小程序端认证服务。
 *
 * <h2>与旧版 Node 实现的三个关键差异</h2>
 *
 * <ol>
 *   <li><b>只有一个登录接口。</b>旧版按角色分成 {@code /api/login} 与
 *       {@code /api/procurement/login} 两个接口，对应 {@code users} 与
 *       {@code procurement_users} 两张表。新版只有一个 {@code /app/auth/login}，
 *       角色作为参数传入并被记录到 {@code app_user_role} 关联表 ——
 *       一个用户可以同时拥有两种身份。</li>
 *
 *   <li><b>用户身份不再由请求参数决定。</b>旧版所有接口靠 {@code user_id} 查询参数
 *       识别用户，改个数字就能看别人的数据。新版一律从 Sa-Token 会话取。</li>
 *   <li><b>注册有事务。</b>创建用户、初始化积分账户、绑定身份三步要么全成功要么全回滚，
 *       不会出现"用户建好了但没有积分账户"的中间态。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppAuthService {

    /** 身份类型：1 回收方 */
    private static final int ROLE_TYPE_RECYCLE = 1;

    /** 身份类型：2 采购方 */
    private static final int ROLE_TYPE_PURCHASE = 2;

    /** 前端传入的角色标识：回收方 */
    private static final String ROLE_LAB = "lab";

    /** 用户未提供昵称时的兜底显示名 */
    private static final String DEFAULT_NICKNAME = "微信用户";

    /** 正常状态 */
    private static final int STATUS_ENABLED = 1;

    private final AppUserMapper appUserMapper;
    private final AppUserRoleMapper appUserRoleMapper;
    private final PointsAccountMapper pointsAccountMapper;
    private final WeChatApiClient weChatApiClient;
    private final PointsService pointsService;

    @Value("${sa-token.timeout:604800}")
    private long tokenTimeoutSeconds;

    /**
     * 登录 / 注册。
     *
     * <p>流程（与前端交互一致）：
     * <pre>
     *   ① 前端 wx.login 拿 code → 调用本接口（不带 userInfo）
     *      → 用户不存在 → 返回 status = "new_user"，前端弹出资料填写表单
     *      → 用户已存在 → 直接返回 token 与 userInfo
     *   ② 前端拿到昵称/头像后 → 再次调用本接口（带 userInfo）
     *      → 创建用户 + 积分账户 + 绑定身份 → 返回 token 与 userInfo
     * </pre>
     */
    @Transactional(rollbackFor = Exception.class)
    public AppLoginVO login(AppLoginDTO dto) {
        String openid = weChatApiClient.getOpenid(dto.getCode());
        AppUser user = findByOpenid(openid);

        if (user == null) {
            // 新用户但还没提供资料 → 只告知前端"需要补资料"，此时**不下发 token**
            if (!hasProfile(dto)) {
                log.info("新用户首次登录，等待补全资料 | openid={}", openid);
                return AppLoginVO.needProfile();
            }
            user = register(openid, dto);
        } else {
            if (user.getStatus() != null && user.getStatus() != STATUS_ENABLED) {
                throw new BizException(ResultCode.USER_DISABLED);
            }
            // 老用户这次带了资料（例如之前跳过了、现在补上）→ 只在字段为空时补
            fillProfileIfBlank(dto);
        }

        // 绑定身份。放在注册之后、登录之前，保证 token 下发时身份已落库
        bindRole(user.getId(), dto.getRole());

        StpAppUtil.login(user.getId());
        // 写入显示名，让后续的操作日志里出现的是昵称而不是 "app:1"
        StpAppUtil.setDisplayName(user.getId(), user.getNickname());

        updateLastLogin(user.getId());

        log.info("小程序登录成功 | userId={} | role={}", user.getId(), dto.getRole());
        return AppLoginVO.of(StpAppUtil.getTokenValue(), tokenTimeoutSeconds, buildUserVO(user));
    }

    /** 当前登录用户的信息（对应前端的 refreshUserInfo） */
    public AppUserVO currentUser() {
        Long userId = StpAppUtil.getLoginIdAsLong();
        AppUser user = appUserMapper.selectById(userId);
        BizException.assertNotNull(user, ResultCode.USER_NOT_FOUND);
        return buildUserVO(user);
    }

    /**
     * 更新用户资料。
     *
     * <p><b>只允许改"用户自己能改"的字段。</b>
     * {@code openid}、{@code status}、{@code total_weight}、{@code carbon_saved}
     * 这些都不在可改范围内 —— 前者是身份标识，后三者是系统累计值。
     * 若图省事直接拿前端传来的对象批量更新，用户就能自己把状态改成启用、
     * 或凭空给自己加碳减排量。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(AppUserVO profile) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        AppUser user = appUserMapper.selectById(userId);
        BizException.assertNotNull(user, ResultCode.USER_NOT_FOUND);

        AppUser update = new AppUser();
        update.setId(userId);
        update.setNickname(profile.getNickname());
        update.setAvatarUrl(profile.getAvatarUrl());
        update.setRealName(profile.getRealName());
        update.setStudentNo(profile.getStudentNo());
        update.setCollege(profile.getCollege());
        update.setLabName(profile.getLabName());
        // phone 需要走微信 getPhoneNumber 的独立流程校验后写入，这里不接受直接修改，
        // 否则用户可以随便填一个不属于自己的手机号
        appUserMapper.updateById(update);

        // 同步更新会话里的显示名，否则操作日志里还是旧昵称
        if (StringUtils.hasText(profile.getNickname())) {
            StpAppUtil.setDisplayName(userId, profile.getNickname());
        }
        log.info("用户更新资料 | userId={}", userId);
    }

    // ==================== 私有方法 ====================

    private AppUser findByOpenid(String openid) {
        // @TableLogic 会自动追加 deleted = 0
        return appUserMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getOpenid, openid));
    }

    private boolean hasProfile(AppLoginDTO dto) {
        return dto.getUserInfo() != null && StringUtils.hasText(dto.getUserInfo().getNickName());
    }

    /**
     * 注册新用户。
     *
     * <p>三步操作在一个事务里：
     * ① 写 app_user；② 初始化 points_account；③ （调用方负责）绑定身份。
     *
     * <p>第 ② 步是必须的：若等到用户第一次用积分时再创建账户，
     * 那么"查余额"这个操作就永远要先判断"账户在不在"，
     * 一旦某处漏判就是 NPE。注册时一次性建好，后续所有查询都可以假定账户存在。
     */
    private AppUser register(String openid, AppLoginDTO dto) {
        AppUser user = new AppUser();
        user.setOpenid(openid);
        user.setNickname(resolveNickname(dto));
        user.setAvatarUrl(dto.getUserInfo() == null ? null : dto.getUserInfo().getAvatarUrl());
        user.setStatus(STATUS_ENABLED);
        // total_weight / carbon_saved 交给数据库默认值 0.00，不在这里显式赋值 ——
        // 同一份默认值只维护一处（DDL），避免两处不一致
        appUserMapper.insert(user);

        PointsAccount account = new PointsAccount();
        account.setUserId(user.getId());
        account.setBalance(0);
        account.setTotalEarn(0);
        account.setTotalSpend(0);
        account.setVersion(0);
        pointsAccountMapper.insert(account);

        // 注册奖励积分。走 PointsService 统一入口而不是直接 UPDATE balance ——
        // 这样才会同时留下积分流水（用户能在"积分明细"里看到"新用户注册奖励"）。
        // 旧版直接在 Node 代码里 UPDATE users SET points = points + 100
        // 并单独插一条流水，两个动作没有事务包裹，中间失败就会出现余额与流水不符。
        pointsService.grantRegisterBonus(user.getId());

        log.info("小程序新用户注册 | userId={} | nickname={}", user.getId(), user.getNickname());
        return user;
    }

    /** 老用户补资料：只在原值为空时填充，不覆盖用户已填的内容 */
    private void fillProfileIfBlank(AppLoginDTO dto) {
        // 说明：微信在 2022 年后不再提供静默获取昵称头像的能力，
        // 因此这里能做的只是"用户主动补填"。真正的更新走 updateProfile 接口。
        // 该方法保留是为了兼容前端在注册流程中重复提交资料的场景。
    }

    private String resolveNickname(AppLoginDTO dto) {
        if (dto.getUserInfo() != null && StringUtils.hasText(dto.getUserInfo().getNickName())) {
            return dto.getUserInfo().getNickName();
        }
        // 微信允许用户拒绝提供昵称，此时给一个可辨识的默认值而不是 null，
        // 避免前端展示空白、也避免操作日志里出现空的操作人
        return DEFAULT_NICKNAME;
    }

    /**
     * 绑定身份。
     *
     * <p>先查再插是常见写法，但**并发下并不可靠**（两个请求可能同时查到"不存在"）。
     * 真正的保证来自 {@code app_user_role} 上的唯一索引
     * {@code uk_user_role_type (user_id, role_type)}：
     * 重复插入会抛 {@link DuplicateKeyException}。
     *
     * <p>这里捕获该异常并降级为 debug 日志，而不是往上抛 ——
     * 因为"身份已存在"恰恰说明目标状态已达成，属于幂等成功。
     * 若让它冒泡，用户会看到一个莫名其妙的"系统繁忙"。
     *
     * <p>注意：MySQL/InnoDB 下唯一键冲突不会让整个事务进入不可用状态，
     * 因此可以安全地捕获后继续。PostgreSQL 则不同（事务会 abort），
     * 那种情况必须用 {@code ON CONFLICT DO NOTHING} 之类的写法。
     */
    private void bindRole(Long userId, String role) {
        int roleType = ROLE_LAB.equalsIgnoreCase(role) ? ROLE_TYPE_RECYCLE : ROLE_TYPE_PURCHASE;

        Long exists = appUserRoleMapper.selectCount(Wrappers.<AppUserRole>lambdaQuery()
                .eq(AppUserRole::getUserId, userId)
                .eq(AppUserRole::getRoleType, roleType));
        if (exists != null && exists > 0) {
            return;
        }

        try {
            AppUserRole userRole = new AppUserRole();
            userRole.setUserId(userId);
            userRole.setRoleType(roleType);
            appUserRoleMapper.insert(userRole);
            log.info("绑定用户身份 | userId={} | roleType={}", userId, roleType);
        } catch (DuplicateKeyException e) {
            log.debug("身份已存在（并发绑定），按幂等处理 | userId={} | roleType={}", userId, roleType);
        }
    }

    private void updateLastLogin(Long userId) {
        AppUser update = new AppUser();
        update.setId(userId);
        update.setLastLoginTime(LocalDateTime.now());
        appUserMapper.updateById(update);
    }

    /**
     * 组装返回给前端的用户信息。
     *
     * <p><b>注意 phone 没有做脱敏。</b>
     * 这是用户查看自己的资料，理应看到完整手机号（资料编辑页要回填）。
     * 脱敏只应出现在"管理员查看他人信息"的场景 ——
     * 把自己的数据脱敏给自己是常见的过度防护，会让功能不可用。
     */
    private AppUserVO buildUserVO(AppUser user) {
        AppUserVO vo = new AppUserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhone(user.getPhone());
        vo.setRealName(user.getRealName());
        vo.setStudentNo(user.getStudentNo());
        vo.setCollege(user.getCollege());
        vo.setLabName(user.getLabName());
        vo.setTotalWeight(user.getTotalWeight());
        vo.setCarbonSaved(user.getCarbonSaved());
        vo.setStatus(user.getStatus());
        vo.setPoints(queryBalance(user.getId()));
        vo.setRoles(queryRoles(user.getId()));
        return vo;
    }

    /** 查询可用积分。账户在注册时已创建，理论上必然存在，缺失时按 0 处理而不是抛异常 */
    private Integer queryBalance(Long userId) {
        PointsAccount account = pointsAccountMapper.selectOne(
                Wrappers.<PointsAccount>lambdaQuery().eq(PointsAccount::getUserId, userId));
        return (account == null || account.getBalance() == null) ? 0 : account.getBalance();
    }

    private List<Integer> queryRoles(Long userId) {
        return appUserRoleMapper.selectList(Wrappers.<AppUserRole>lambdaQuery()
                        .eq(AppUserRole::getUserId, userId))
                .stream()
                .map(AppUserRole::getRoleType)
                .filter(Objects::nonNull)
                .toList();
    }
}
