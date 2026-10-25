package com.nqx.service.system;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.SysPermissionMapper;
import com.nqx.mapper.SysRoleMapper;
import com.nqx.mapper.SysRolePermissionMapper;
import com.nqx.mapper.SysUserMapper;
import com.nqx.mapper.SysUserRoleMapper;
import com.nqx.pojo.dto.system.SysRoleQuery;
import com.nqx.pojo.dto.system.SysRoleSaveDTO;
import com.nqx.pojo.entity.SysPermission;
import com.nqx.pojo.entity.SysRole;
import com.nqx.pojo.entity.SysRolePermission;
import com.nqx.pojo.entity.SysUser;
import com.nqx.pojo.entity.SysUserRole;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.system.SysRoleVO;
import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色服务（RBAC 的角色侧）。
 *
 * <p>角色是「管理员」与「权限」之间的唯一桥梁，因此这里的删除与授权操作
 * 都会牵动两侧的关联表，每一步都要同时考虑关联数据的完整性：
 * 删角色要清 {@code sys_role_permission}，改权限要清管理员的权限缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysRoleService {

    /**
     * 超级管理员角色 ID。
     *
     * <p>与 {@code SysUserService} 里保护 id=1 的账号同理：角色 id=1 是整个权限体系的根，
     * 删掉它之后所有授予都给不出去了（其下的关联行会成为孤儿数据），
     * 而且没有任何界面能把它恢复回来。
     */
    private static final long SUPER_ADMIN_ROLE_ID = 1L;

    /** 出参 DTO 里的角色标识长度上限，与 {@code sys_role.role_code} 的 VARCHAR(50) 对齐 */
    private static final int ROLE_CODE_MAX_LENGTH = 50;

    private final SysRoleMapper sysRoleMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final AdminPermissionCacheEvictor permissionCacheEvictor;

    // ==================== 查询 ====================

    public PageResult<SysRoleVO> page(SysRoleQuery query) {
        Page<SysRole> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SysRole> result = sysRoleMapper.selectPage(page,
                Wrappers.<SysRole>lambdaQuery()
                        .like(StringUtils.hasText(query.getRoleCode()), SysRole::getRoleCode, query.getRoleCode())
                        .like(StringUtils.hasText(query.getRoleName()), SysRole::getRoleName, query.getRoleName())
                        .eq(query.getStatus() != null, SysRole::getStatus, query.getStatus())
                        .orderByAsc(SysRole::getSort)
                        .orderByAsc(SysRole::getId));

        PageResult<SysRoleVO> pageResult = PageUtil.of(result, SysRoleVO::from);
        fillUserCount(pageResult.getRecords());
        return pageResult;
    }

    /**
     * 全部角色（不分页），供下拉选择使用。
     *
     * <p>刻意<b>不过滤 status</b>：如果只返回启用角色，
     * 那么一个已被禁用的角色在「分配角色」弹窗里就会消失，
     * 管理员既看不到它、也就无法把某个账号从该角色上摘下来 ——
     * 数据坏了却没有界面能修。列表里带上 status 让前端自行标注即可。
     */
    public List<SysRoleVO> list() {
        List<SysRoleVO> roles = sysRoleMapper.selectList(
                        Wrappers.<SysRole>lambdaQuery()
                                .orderByAsc(SysRole::getSort)
                                .orderByAsc(SysRole::getId))
                .stream()
                .map(SysRoleVO::from)
                .toList();
        fillUserCount(roles);
        return roles;
    }

    /** 某角色已分配的权限 ID 集合，供「分配权限」弹窗回填勾选项 */
    public List<Long> getPermissionIds(Long roleId) {
        BizException.assertNotNull(sysRoleMapper.selectById(roleId), ResultCode.ROLE_NOT_FOUND);
        return sysRolePermissionMapper.selectList(
                        Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleId, roleId))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .toList();
    }

    // ==================== 增删改 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long create(SysRoleSaveDTO dto) {
        String roleCode = dto.getRoleCode().trim();
        checkRoleCodeUnique(roleCode, null);

        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        applyEditableFields(role, dto);
        role.setStatus(dto.getStatus() == null ? CommonConstants.STATUS_ENABLED : dto.getStatus());
        role.setDeleted(CommonConstants.DELETED_NO);
        sysRoleMapper.insert(role);

        log.info("新增角色 | id={} | roleCode={} | 操作人={}",
                role.getId(), roleCode, StpAdminUtil.getLoginIdDefaultNull());
        return role.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysRoleSaveDTO dto) {
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        SysRole exists = sysRoleMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.ROLE_NOT_FOUND);

        // 超管角色的两条保护：标识不许改（否则 hasRole('SUPER_ADMIN') 之类的
        // 硬编码判断会静默失效），状态不许禁用（禁用后它就不再参与鉴权，
        // 等于把整个权限体系关掉）
        if (dto.getId() == SUPER_ADMIN_ROLE_ID) {
            if (!exists.getRoleCode().equals(dto.getRoleCode().trim())) {
                throw new BizException(ResultCode.SUPER_ADMIN_PROTECTED, "超级管理员角色的角色标识不允许修改");
            }
            if (dto.getStatus() != null && dto.getStatus() == CommonConstants.STATUS_DISABLED) {
                throw new BizException(ResultCode.SUPER_ADMIN_PROTECTED, "超级管理员角色不允许禁用");
            }
        }

        String roleCode = dto.getRoleCode().trim();
        checkRoleCodeUnique(roleCode, dto.getId());

        SysRole role = new SysRole();
        role.setId(dto.getId());
        role.setRoleCode(roleCode);
        applyEditableFields(role, dto);
        role.setStatus(dto.getStatus());
        sysRoleMapper.updateById(role);

        // 角色的「启用状态」与「角色标识」都是鉴权链路的一部分：
        // 权限查询 SQL 里带 r.status = 1，而角色标识会被缓存进会话。
        // 只有真正发生了变化才需要清缓存，否则每次改个描述都要让
        // 该角色下所有管理员的缓存失效，白白多出几次数据库往返
        boolean statusChanged = dto.getStatus() != null && !dto.getStatus().equals(exists.getStatus());
        boolean codeChanged = !exists.getRoleCode().equals(roleCode);
        if (statusChanged || codeChanged) {
            permissionCacheEvictor.evictAll(findUserIdsByRoleId(dto.getId()));
        }

        log.info("修改角色 | id={} | roleCode={} | 操作人={}",
                dto.getId(), roleCode, StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 删除角色。
     *
     * <p>两道拦截，都是为了守住数据一致性的底线：
     * <ol>
     *   <li>超管角色直接拒绝 —— 它是权限体系的根，删掉后没有界面能恢复；</li>
     *   <li>角色下还有管理员时拒绝，并告诉管理员「还有几个」。
     *       这里<b>不能</b>为了"方便"顺手把 {@code sys_user_role} 一起删掉：
     *       那会让这些账号在无人察觉的情况下失去全部权限，
     *       属于典型的"删除操作产生了用户没预期的副作用"。</li>
     * </ol>
     * 关联权限（{@code sys_role_permission}）则可以安全清理：
     * 角色没了，这些授权记录也就没有任何含义了。
     *
     * <p><b>⚠️ 关于唯一索引的坑（V13 已从表结构层面解决）</b>
     * {@code uk_role_code(role_code, deleted)} 原本会与 {@code sys_user} 的用户名索引
     * 犯同一个毛病：逻辑删除只把 deleted 置 1，同一个角色标识第二次被删除时
     * 会出现两行 {@code (role_code, 1)} 并撞唯一键。
     *
     * <p>本方法最初是在业务层"退避"规避（把 role_code 改成 {@code 原标识#del#<id>}），
     * 但那会**把 role_code 这个审计字段改坏** —— 事后根本看不出这个被删的角色原来叫什么。
     * V13 把 deleted 放宽为 BIGINT，删除时改写**本行 id**，
     * 唯一索引自然不再冲突，而 role_code 保持原样。
     * 能用表结构解决的事，不该让业务代码去迁就。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole exists = sysRoleMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.ROLE_NOT_FOUND);

        if (id == SUPER_ADMIN_ROLE_ID) {
            throw new BizException(ResultCode.SUPER_ADMIN_PROTECTED, "超级管理员角色受保护，不允许删除");
        }

        List<Long> affectedUserIds = findUserIdsByRoleId(id);
        if (!affectedUserIds.isEmpty()) {
            throw new BizException(ResultCode.ROLE_IN_USE,
                    "该角色下还有 " + affectedUserIds.size() + " 个管理员，请先解除关联后再删除");
        }

        // deleted 写本行 id 而不是 1：这样同一 role_code 可以被删除任意多次
        // （唯一索引 uk_role_code(role_code, deleted) 的取值各不相同），
        // 而 role_code 本身保持原样，审计线索完整。
        // 显式带 deleted = 0 条件，确保只可能改到未被删除的那一行（幂等保护）。
        sysRoleMapper.update(null, Wrappers.<SysRole>update()
                .set("deleted", id)
                .eq("id", id)
                .eq("deleted", CommonConstants.DELETED_NO));
        sysRolePermissionMapper.delete(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleId, id));

        log.info("删除角色 | id={} | roleCode={} | 操作人={}",
                id, exists.getRoleCode(), StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 给角色分配权限（全删全插）。
     *
     * <p>与分配角色同理：全删全插的语义是「以本次提交为准」，
     * 前端树形多选组件提交的本来就是一份完整的勾选结果，
     * 计算差集反而容易在并发或前端漏传时留下不一致的中间状态。
     *
     * <p>入参 {@code distinct} 不能省：{@code sys_role_permission} 上有
     * {@code uk_role_permission(role_id, permission_id)} 唯一键，
     * 重复 ID 会让插入撞唯一键并抛出 500。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        BizException.assertNotNull(sysRoleMapper.selectById(roleId), ResultCode.ROLE_NOT_FOUND);

        List<Long> distinctIds = permissionIds == null
                ? List.of()
                : permissionIds.stream().filter(Objects::nonNull).distinct().toList();

        if (!distinctIds.isEmpty()) {
            Long permCount = sysPermissionMapper.selectCount(
                    Wrappers.<SysPermission>lambdaQuery().in(SysPermission::getId, distinctIds));
            if (permCount == null || permCount != distinctIds.size()) {
                // 权限可能已被别的管理员删除，直接拒绝而不是插入孤儿关联
                throw new BizException(ResultCode.PARAM_ERROR, "勾选的权限中包含已被删除的节点，请刷新后重试");
            }
        }

        sysRolePermissionMapper.delete(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getRoleId, roleId));
        for (Long permissionId : distinctIds) {
            SysRolePermission relation = new SysRolePermission();
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            sysRolePermissionMapper.insert(relation);
        }

        // 授权结果必须立刻生效，否则会出现「刚取消的权限还能用」
        permissionCacheEvictor.evictAll(findUserIdsByRoleId(roleId));

        log.info("分配角色权限 | roleId={} | 权限数={} | 操作人={}",
                roleId, distinctIds.size(), StpAdminUtil.getLoginIdDefaultNull());
    }

    // ==================== 私有方法 ====================

    /**
     * 统计每个角色下的管理员数量（仅统计未被逻辑删除的账号）。
     *
     * <p>用「一次取回关联表 + 一次取回在用账号 ID，再在内存里分组」，
     * 而不是手写 JOIN 聚合 SQL：{@code sys_user_role} 的行数等于
     * 「管理员数 × 每人平均角色数」，在后台场景下是几十行的量级，
     * 一次全量取回的开销可以忽略；而聚合 SQL 需要 JOIN {@code sys_user}
     * 才能排除已删除账号，MyBatis-Plus 的条件构造器表达不了，就得再写一个 XML
     * —— 多一份需要跟着表结构维护的 SQL，换来的收益在这个数据量下并不存在。
     *
     * <p>列表页的「关联管理员数」与删除时的拦截提示共用这一个方法，
     * 保证界面显示的数字和拦截时说的数字永远一致。
     */
    private Map<Long, Integer> countUsersByRole() {
        List<SysUserRole> relations = sysUserRoleMapper.selectList(null);
        if (relations.isEmpty()) {
            return Map.of();
        }
        // 只 select 主键，避免把账号、密码等字段一并拉回来
        Set<Long> aliveUserIds = sysUserMapper
                .selectList(Wrappers.<SysUser>lambdaQuery().select(SysUser::getId))
                .stream()
                .map(SysUser::getId)
                .collect(Collectors.toSet());

        Map<Long, Integer> counts = new HashMap<>();
        for (SysUserRole relation : relations) {
            if (aliveUserIds.contains(relation.getUserId())) {
                counts.merge(relation.getRoleId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void fillUserCount(List<SysRoleVO> roles) {
        if (roles == null || roles.isEmpty()) {
            return;
        }
        Map<Long, Integer> counts = countUsersByRole();
        for (SysRoleVO vo : roles) {
            vo.setUserCount(counts.getOrDefault(vo.getId(), 0));
        }
    }

    /** 拥有该角色的管理员 ID（已排除逻辑删除的账号） */
    private List<Long> findUserIdsByRoleId(Long roleId) {
        List<Long> userIds = sysUserRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, roleId))
                .stream()
                .map(SysUserRole::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper
                .selectList(Wrappers.<SysUser>lambdaQuery()
                        .select(SysUser::getId)
                        .in(SysUser::getId, userIds))
                .stream()
                .map(SysUser::getId)
                .toList();
    }

    /**
     * 角色标识唯一性校验。
     *
     * <p>数据库上 {@code uk_role_code(role_code, deleted)} 是普通唯一索引，
     * 撞车时抛的是 500；先在业务层拦一道，才能给出「角色标识已存在」这种可读提示。
     * 修改时通过 {@code excludeId} 把自己排除掉，否则「只改个名字不碰标识」
     * 也会被自己挡住。
     */
    private void checkRoleCodeUnique(String roleCode, Long excludeId) {
        Long count = sysRoleMapper.selectCount(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getRoleCode, roleCode)
                        .ne(excludeId != null, SysRole::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ResultCode.ROLE_CODE_EXISTS, "角色标识「" + roleCode + "」已存在，请更换");
        }
    }

    /** DTO → 实体（不含 roleCode，它在调用处单独处理以做唯一性校验） */
    private void applyEditableFields(SysRole role, SysRoleSaveDTO dto) {
        role.setRoleName(dto.getRoleName());
        role.setDescription(dto.getDescription());
        role.setSort(dto.getSort() == null ? 0 : dto.getSort());
    }
}
