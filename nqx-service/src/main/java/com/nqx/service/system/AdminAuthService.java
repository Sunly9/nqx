package com.nqx.service.system;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.constant.RedisKeyConstants;
import com.nqx.common.exception.BizException;
import com.nqx.common.util.ServletUtils;
import com.nqx.mapper.SysLoginLogMapper;
import com.nqx.mapper.SysPermissionMapper;
import com.nqx.mapper.SysUserMapper;
import com.nqx.pojo.dto.system.AdminLoginDTO;
import com.nqx.pojo.entity.SysLoginLog;
import com.nqx.pojo.entity.SysUser;
import com.nqx.pojo.util.MenuTreeBuilder;
import com.nqx.pojo.vo.system.AdminInfoVO;
import com.nqx.pojo.vo.system.AdminLoginVO;
import com.nqx.pojo.vo.system.MenuVO;
import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端认证服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** 连续登录失败达到该次数后临时锁定 */
    private static final int MAX_LOGIN_FAIL_TIMES = 5;

    /** 失败计数的保留窗口 */
    private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(15);

    private final SysUserMapper sysUserMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final SysLoginLogMapper sysLoginLogMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${sa-token.timeout:604800}")
    private long tokenTimeoutSeconds;

    /**
     * 管理员登录。
     *
     * <p>完整流程与每一步的安全考量：
     * <ol>
     *   <li><b>先查失败次数再查数据库</b> —— 暴力破解时能挡住绝大部分请求，
     *       不让它们打到数据库；</li>
     *   <li><b>账号不存在与密码错误返回同一个提示</b> —— 若分别提示，
     *       攻击者就能通过提示差异枚举出系统里存在哪些账号（账号枚举漏洞）；</li>
     *   <li><b>密码校验用 BCrypt</b> —— 它是慢哈希（默认 10 轮），
     *       即便数据库泄露，暴力破解成本也极高；</li>
     *   <li><b>登录成功清空失败计数</b>，避免用户白天失败过几次、
     *       晚上正常登录却被累计锁定。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminLoginVO login(AdminLoginDTO dto) {
        String username = dto.getUsername().trim();
        String failKey = RedisKeyConstants.LOGIN_FAIL_PREFIX + username;

        // 1. 检查是否已被临时锁定
        String failCountStr = redisTemplate.opsForValue().get(failKey);
        if (failCountStr != null && Integer.parseInt(failCountStr) >= MAX_LOGIN_FAIL_TIMES) {
            log.warn("登录失败次数超限 | username={} | ip={}", username, ServletUtils.getClientIp());
            throw new BizException(ResultCode.TOO_MANY_LOGIN_ATTEMPTS);
        }

        // 2. 查询账号（@TableLogic 会自动追加 deleted = 0）
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));

        // 3. 账号不存在 / 密码错误 —— 统一处理
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            long fails = increaseFailCount(failKey);
            writeLoginLog(null, username, 0, "账号或密码错误");
            log.warn("登录失败 | username={} | ip={} | 累计失败={}次",
                    username, ServletUtils.getClientIp(), fails);
            throw new BizException(ResultCode.PASSWORD_ERROR);
        }

        // 4. 状态检查放在密码校验之后：避免通过状态提示反推账号是否存在
        if (user.getStatus() == null || user.getStatus() != 1) {
            writeLoginLog(user.getId(), username, 0, "账号已禁用");
            throw new BizException(ResultCode.USER_DISABLED);
        }

        // 5. 登录成功
        StpAdminUtil.login(user.getId());
        // 把显示名写入会话，供操作日志记录可读的操作人名称。
        // 否则日志的 user_name 只能退化为 "admin:1" 这种标识，人看着别扭。
        StpAdminUtil.setDisplayName(user.getId(), user.getUsername());
        redisTemplate.delete(failKey);

        // 6. 更新最后登录信息
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(ServletUtils.getClientIp());
        sysUserMapper.updateById(update);

        writeLoginLog(user.getId(), username, 1, "登录成功");
        log.info("管理员登录成功 | userId={} | username={} | ip={}",
                user.getId(), username, ServletUtils.getClientIp());

        // 7. 组装返回：直接带上用户信息与权限码，省掉前端登录后立刻再请求一次 /info
        AdminLoginVO vo = new AdminLoginVO();
        vo.setToken(StpAdminUtil.getTokenValue());
        vo.setExpiresIn(tokenTimeoutSeconds);
        vo.setUser(buildAdminInfo(user));
        return vo;
    }

    /** 当前登录管理员的信息与权限码 */
    public AdminInfoVO currentAdminInfo() {
        Long userId = StpAdminUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        BizException.assertNotNull(user, ResultCode.USER_NOT_FOUND);
        return buildAdminInfo(user);
    }

    /** 当前登录管理员的动态菜单树 */
    public List<MenuVO> currentMenus() {
        Long userId = StpAdminUtil.getLoginIdAsLong();
        return MenuTreeBuilder.build(sysPermissionMapper.selectMenusByUserId(userId));
    }

    // ==================== 私有方法 ====================

    private AdminInfoVO buildAdminInfo(SysUser user) {
        AdminInfoVO vo = new AdminInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(ServletUtils.getRequest() == null ? user.getPhone() : maskPhone(user.getPhone()));
        vo.setEmail(user.getEmail());
        vo.setRoles(StpAdminUtil.getRoleList());
        vo.setPermissions(StpAdminUtil.getPermissionList());
        return vo;
    }

    private long increaseFailCount(String failKey) {
        Long fails = redisTemplate.opsForValue().increment(failKey);
        // 首次失败时设置过期时间。若每次失败都重置过期时间，
        // 攻击者持续慢速尝试就能让计数永不过期，反而把自己锁死；
        // 只在第一次设置，则形成"固定窗口"语义，到期自动解封。
        if (fails != null && fails == 1L) {
            redisTemplate.expire(failKey, LOGIN_FAIL_WINDOW);
        }
        return fails == null ? 1L : fails;
    }

    private void writeLoginLog(Long userId, String username, int status, String msg) {
        SysLoginLog logEntity = new SysLoginLog();
        logEntity.setUserId(userId);
        // 必须用统一常量，不能硬编码数字。
        // 这里曾是硬编码的 1，而 1 在统一约定里表示「小程序用户」，
        // 导致同一个管理员在 sys_login_log 与 sys_operation_log 里的类型值不同（已由 V4 修正）。
        logEntity.setUserType(CommonConstants.OPERATOR_TYPE_ADMIN);
        logEntity.setUserName(username);
        logEntity.setIp(ServletUtils.getClientIp());
        logEntity.setBrowser(ServletUtils.getUserAgent());
        logEntity.setStatus(status);
        logEntity.setMsg(msg);
        sysLoginLogMapper.insert(logEntity);
    }

    /** 手机号脱敏：138****8888 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
