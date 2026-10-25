package com.nqx.service.system;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.SysRoleMapper;
import com.nqx.mapper.SysUserMapper;
import com.nqx.mapper.SysUserRoleMapper;
import com.nqx.pojo.dto.system.SysUserQuery;
import com.nqx.pojo.dto.system.SysUserSaveDTO;
import com.nqx.pojo.entity.SysRole;
import com.nqx.pojo.entity.SysUser;
import com.nqx.pojo.entity.SysUserRole;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.system.SysUserVO;
import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理员（后台账号）服务。
 *
 * <p><b>本类的核心不是增删改查，而是几条「防止系统把自己锁死」的保护规则。</b>
 * 后台账号管理是所有权限体系的根：一旦把最后的超级管理员删掉或禁用掉，
 * 就没有任何人能再登录后台把它改回来 —— 这类故障无法通过业务操作恢复，
 * 只能改数据库。因此下面这些规则宁可写得啰嗦，也不能省：
 * <ol>
 *   <li>不能删除当前登录账号；</li>
 *   <li>不能删除、禁用 id=1 的超级管理员；</li>
 *   <li>id=1 必须始终保留「超级管理员」角色；</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserService {

    /**
     * 超级管理员账号 ID。
     *
     * <p>为什么把 1 写死成常量而不是「查 roleCode = SUPER_ADMIN 的账号」？
     * 因为保护规则必须在<b>角色数据本身被改坏</b>时依然有效 ——
     * 如果判断依赖角色表，那么「先把超管角色删掉、再删账号」就能绕过保护。
     * 用主键做锚点是唯一不依赖其他表的兜底方式，它由初始化脚本保证存在。
     */
    private static final long SUPER_ADMIN_USER_ID = 1L;

    /** 超级管理员角色的标识，用于校验 id=1 的账号不被剥夺超管身份 */
    private static final String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    /** 密码最小长度。与 DTO 上的 @Size 保持一致：入口校验给用户友好提示，这里防绕过 */
    private static final int MIN_PASSWORD_LENGTH = 6;

    /** 出参 DTO 里的用户名长度上限，与 {@code sys_user.username} 的 VARCHAR(50) 对齐 */
    private static final int USERNAME_MAX_LENGTH = 50;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final AdminPermissionCacheEvictor permissionCacheEvictor;

    // ==================== 查询 ====================

    /**
     * 多条件分页检索。
     *
     * <p>条件用 MyBatis-Plus 的条件构造器拼装，没有再写一份手写 SQL：
     * 这里全部是单表等值/模糊匹配，条件构造器已经能表达清楚，
     * 而多一个 XML 就多一处需要跟着表结构一起维护的地方。
     * （逻辑删除 {@code deleted = 0} 由 {@code @TableLogic} 自动追加。）
     */
    public PageResult<SysUserVO> page(SysUserQuery query) {
        Page<SysUser> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SysUser> result = sysUserMapper.selectPage(page,
                Wrappers.<SysUser>lambdaQuery()
                        .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                        .like(StringUtils.hasText(query.getRealName()), SysUser::getRealName, query.getRealName())
                        .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                        // 新增的账号排在前面，符合「刚建完就想看到它」的使用习惯
                        .orderByDesc(SysUser::getId));

        PageResult<SysUserVO> pageResult = PageUtil.of(result, SysUserVO::from);
        fillRoles(pageResult.getRecords());
        return pageResult;
    }

    /** 某管理员已分配的角色 ID 集合，供「分配角色」弹窗回填勾选项 */
    public List<Long> getRoleIds(Long userId) {
        BizException.assertNotNull(sysUserMapper.selectById(userId), ResultCode.USER_NOT_FOUND);
        return sysUserRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
    }

    // ==================== 增删改 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long create(SysUserSaveDTO dto) {
        String username = dto.getUsername().trim();
        checkUsernameUnique(username);

        if (!StringUtils.hasText(dto.getPassword())) {
            // 新增必须给初始密码：留空的话 BCrypt 会存下一个空串的哈希，
            // 表面上"创建成功"，实际这个账号永远登不进来，问题要等用户反馈才暴露
            throw new BizException(ResultCode.PARAM_ERROR, "请设置初始密码");
        }
        checkPasswordLength(dto.getPassword());

        SysUser user = new SysUser();
        // 账号统一去除首尾空格：登录时 AdminAuthService 也做了 trim，
        // 两边不一致会导致"创建时看着没问题、登录时却报账号不存在"
        user.setUsername(username);
        // 密码绝不能 trim —— 空格是密码的合法组成部分，
        // 若创建时去掉、登录时不去掉（或反之），用户就会遇到无法解释的登录失败
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        applyEditableFields(user, dto);
        user.setStatus(dto.getStatus() == null ? CommonConstants.STATUS_ENABLED : dto.getStatus());
        user.setDeleted(CommonConstants.DELETED_NO);
        sysUserMapper.insert(user);

        log.info("新增管理员 | id={} | username={} | 操作人={}",
                user.getId(), username, StpAdminUtil.getLoginIdDefaultNull());
        return user.getId();
    }

    /**
     * 修改管理员资料。
     *
     * <p><b>刻意不修改的字段：{@code username}、{@code password}、{@code status}。</b>
     * 它们各自都有专门的入口，混进「编辑资料」这个动作里会带来两个问题：
     * <ul>
     *   <li>账号与密码是登录凭据，改动应当是一个独立、显式、可审计的动作；
     *       改密码必须走「重置密码」接口（有独立的权限码 {@code sys:user:resetPwd}），
     *       否则只有「修改管理员」权限的人就能顺手重置别人的密码，等于提权；</li>
     *   <li>状态必须走 {@code /status} 接口，否则超管保护、禁止禁用自己这些规则
     *       会被编辑表单直接绕过。</li>
     * </ul>
     * 实现上只把可改字段塞进 update 对象 —— MyBatis-Plus 默认的 NOT_NULL 更新策略
     * 会自动跳过 null 字段，所以「没赋值」就等于「不修改」，不需要额外写 SQL。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(SysUserSaveDTO dto) {
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        SysUser exists = sysUserMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);

        SysUser update = new SysUser();
        update.setId(dto.getId());
        applyEditableFields(update, dto);
        sysUserMapper.updateById(update);

        log.info("修改管理员 | id={} | 操作人={}", dto.getId(), StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 启用 / 禁用。
     *
     * <p>禁用会同时注销该账号的全部会话。这一步不能省：Sa-Token 把角色与权限码
     * 缓存在会话里，不注销的话被禁用的管理员手里的 token 依然可用，
     * 直到 7 天有效期结束 —— 「禁用」这个动作实际上没有立即生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != CommonConstants.STATUS_ENABLED
                && status != CommonConstants.STATUS_DISABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "状态值只能是 0（禁用）或 1（启用）");
        }
        SysUser exists = sysUserMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);

        if (status == CommonConstants.STATUS_DISABLED) {
            checkNotSuperAdmin(id, "禁用");
            if (id.equals(StpAdminUtil.getLoginIdDefaultNull())) {
                // 与「不能删除自己」同理：禁用自己会立刻被踢下线，且没有别人能帮忙启用回来
                throw new BizException(ResultCode.PARAM_ERROR, "不能禁用当前登录的账号");
            }
        }

        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        sysUserMapper.updateById(update);

        if (status == CommonConstants.STATUS_DISABLED) {
            StpAdminUtil.logout(id);
        }
        log.info("管理员状态变更 | id={} | status={} | 操作人={}",
                id, status, StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 重置密码。
     *
     * <p>只覆盖 {@code password} 一个字段，不做任何"顺手"的更新 ——
     * 这个接口的权限码与「修改管理员」是分开的，方法本身也必须同样收敛。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String rawPassword) {
        SysUser exists = sysUserMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);
        // DTO 上的 @Size 是给用户看的友好提示，这里再校验一次才是真正的边界：
        // Service 是比 Controller 更底层、会被更多调用方复用的入口
        checkPasswordLength(rawPassword);

        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(BCrypt.hashpw(rawPassword));
        sysUserMapper.updateById(update);

        log.info("重置管理员密码 | id={} | username={} | 操作人={}",
                id, exists.getUsername(), StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 分配角色。
     *
     * <p>采用「先全删、再全插」而不是计算差集：
     * 差集需要额外两次查询且容易在并发下算错，而这个接口的调用频率极低
     * （只在管理员手工调整时触发），全删全插语义简单、结果确定。
     * 整体在一个事务里，中途失败会回滚，不会出现「旧的删了、新的没插上」的中间态。
     *
     * <p>注意入参先做了 {@code distinct}：{@code sys_user_role} 上有
     * {@code uk_user_role(user_id, role_id)} 唯一键，前端只要重复传一次同一个角色，
     * 插入就会撞唯一键并抛出 500。这类「前端传得不干净就炸库」的问题必须在服务端兜住。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser exists = sysUserMapper.selectById(userId);
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);

        List<Long> distinctRoleIds = roleIds == null
                ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();

        List<SysRole> roles = distinctRoleIds.isEmpty()
                ? List.of()
                : sysRoleMapper.selectList(Wrappers.<SysRole>lambdaQuery().in(SysRole::getId, distinctRoleIds));
        if (roles.size() != distinctRoleIds.size()) {
            // 角色可能已被删除或 ID 是伪造的，直接拒绝，避免往关联表里写孤儿数据
            throw new BizException(ResultCode.ROLE_NOT_FOUND);
        }

        // 超管账号必须保留超管角色，否则它就退化成一个普通账号，
        // 系统的兜底入口就没了
        if (userId == SUPER_ADMIN_USER_ID
                && roles.stream().noneMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getRoleCode()))) {
            throw new BizException(ResultCode.SUPER_ADMIN_PROTECTED,
                    "超级管理员账号必须保留「超级管理员」角色");
        }

        sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        for (Long roleId : distinctRoleIds) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            sysUserRoleMapper.insert(relation);
        }

        // 角色变了，会话里缓存的权限码就过期了。不清理的话，
        // 被收回的权限在这个管理员的 token 有效期内仍然可用
        permissionCacheEvictor.evict(userId);

        log.info("分配管理员角色 | id={} | roleIds={} | 操作人={}",
                userId, distinctRoleIds, StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 删除管理员（逻辑删除）。
     *
     * <p>删除的同时清理 {@code sys_user_role}：
     * 关联表没有逻辑删除列，账号"消失"后这些行就是纯脏数据；
     * 更麻烦的是角色删除时的「该角色下还有 N 个管理员」计数会把它们算进去，
     * 导致一个实际已经没人使用的角色永远删不掉。
     *
     * <p><b>⚠️ 关于唯一索引的坑（V13 已从表结构层面解决）</b>
     * {@code sys_user} 上的唯一索引是 {@code uk_username(username, deleted)}，
     * 而逻辑删除只是把 {@code deleted} 从 0 改成 1。于是同一个账号<b>第二次</b>被删除时，
     * 库里会同时出现两行 {@code (username, 1)}，直接撞唯一键并抛
     * {@code DuplicateKeyException}（表现为 500「系统繁忙」）。
     * 也就是说「创建 → 删除 → 再创建同名账号 → 再删除」这条完全正常的运维路径
     * 必然失败，且失败信息对管理员毫无意义。
     *
     * <p>本方法最初是在业务层"退避"规避（删除前把账号改成 {@code 原名#del#<id>}）。
     * 它确实能绕开撞键，但**把 username 这个审计字段改坏了** ——
     * 事后根本看不出这个被删的账号原来叫什么。
     *
     * <p>V13 从表结构层面正解：把 {@code deleted} 放宽为 BIGINT，
     * 删除时写入**本行 id**。于是唯一索引的语义变成
     * 「未删除的行 deleted=0（用户名仍全局唯一）；已删除的行 deleted=行 id（每次删除都不同）」，
     * 而用户名保持原样。<b>能用表结构解决的事，不该让业务代码去迁就。</b>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser exists = sysUserMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.USER_NOT_FOUND);

        if (id.equals(StpAdminUtil.getLoginIdDefaultNull())) {
            throw new BizException(ResultCode.CANNOT_DELETE_SELF);
        }
        checkNotSuperAdmin(id, "删除");

        // deleted 写本行 id 而不是 1：这样同一 username 可以被删除任意多次
        // （唯一索引 uk_username(username, deleted) 的取值各不相同），
        // 而 username 本身保持原样，审计线索完整。
        // 显式带 deleted = 0 条件，确保只可能改到未被删除的那一行（幂等保护）。
        sysUserMapper.update(null, Wrappers.<SysUser>update()
                .set("deleted", id)
                .eq("id", id)
                .eq("deleted", CommonConstants.DELETED_NO));

        sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, id));
        // 会话也要一起注销，否则被删账号的 token 在有效期内仍然可用
        StpAdminUtil.logout(id);

        log.info("删除管理员 | id={} | username={} | 操作人={}",
                id, exists.getUsername(), StpAdminUtil.getLoginIdDefaultNull());
    }

    // ==================== 私有方法 ====================

    /**
     * 批量回填角色的 ID 与名称。
     *
     * <p>刻意用两次批量查询在内存里拼装，而不是「循环里按 userId 逐条查角色」：
     * 后者是典型的 N+1 查询，一页 100 条就是 200 次数据库往返。
     * 这里固定 2 次查询，与页大小无关。
     */
    private void fillRoles(List<SysUserVO> users) {
        if (users == null || users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(SysUserVO::getId).toList();
        List<SysUserRole> relations = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds));
        if (relations.isEmpty()) {
            return;
        }

        List<Long> roleIds = relations.stream().map(SysUserRole::getRoleId).distinct().toList();
        Map<Long, String> roleNameMap = sysRoleMapper
                .selectList(Wrappers.<SysRole>lambdaQuery().in(SysRole::getId, roleIds))
                .stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));

        Map<Long, List<SysUserRole>> relationsByUser = relations.stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId));

        for (SysUserVO vo : users) {
            List<SysUserRole> mine = relationsByUser.getOrDefault(vo.getId(), List.of());
            vo.setRoleIds(mine.stream().map(SysUserRole::getRoleId).toList());
            vo.setRoleNames(mine.stream()
                    .map(relation -> roleNameMap.get(relation.getRoleId()))
                    .filter(Objects::nonNull)
                    .toList());
        }
    }

    /**
     * 账号唯一性校验。
     *
     * <p>数据库上有 {@code uk_username(username, deleted)}，但它带来的是 500 错误，
     * 而不是「该账号已存在」这种可读提示，因此必须在业务层先拦一道。
     * 查询由 {@code @TableLogic} 自动追加 {@code deleted = 0}，
     * 与索引里 {@code deleted = 0} 这一半的语义恰好一致 ——
     * 也就是说「账号被逻辑删除后可以重新创建」这条规则在两层是统一的。
     */
    private void checkUsernameUnique(String username) {
        Long count = sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (count != null && count > 0) {
            throw new BizException(ResultCode.USERNAME_EXISTS, "登录账号「" + username + "」已存在，请更换");
        }
    }

    private void checkPasswordLength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(ResultCode.PARAM_ERROR, "密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    /** 超级管理员账户的保护校验 */
    private void checkNotSuperAdmin(Long id, String action) {
        if (id != null && id == SUPER_ADMIN_USER_ID) {
            throw new BizException(ResultCode.SUPER_ADMIN_PROTECTED,
                    "超级管理员账号受保护，不允许" + action);
        }
    }

    /** DTO → 实体的「可编辑字段」拷贝，用户名/密码/状态刻意不在其中 */
    private void applyEditableFields(SysUser user, SysUserSaveDTO dto) {
        user.setRealName(dto.getRealName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setAvatar(dto.getAvatar());
        user.setRemark(dto.getRemark());
    }
}
