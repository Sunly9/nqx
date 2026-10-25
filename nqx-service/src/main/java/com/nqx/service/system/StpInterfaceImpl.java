package com.nqx.service.system;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import com.nqx.mapper.SysPermissionMapper;
import com.nqx.mapper.SysRoleMapper;
import com.nqx.service.satoken.StpAdminLogic;
import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据源。
 *
 * <p>实现 {@link StpInterface} 后，Sa-Token 在执行
 * {@code @SaCheckPermission}、{@code @SaCheckRole}、{@code hasPermission()} 时
 * 会自动回调本类来获取该用户拥有的权限与角色，
 * 业务代码无需手动往会话里塞权限数据。
 *
 * <p><b>缓存在哪里 —— 这是本类最需要想清楚的问题</b>
 *
 * <p>鉴权是超高频操作：一个后台列表页可能触发多个 {@code @SaCheckPermission} 校验。
 * 如果每次校验都去数据库做四表 JOIN，接口响应时间和数据库压力都会很难看。
 *
 * <p>因此把结果缓存在 <b>Sa-Token 的 Account-Session</b> 里：
 * <ul>
 *   <li>Account-Session 存储于 Redis（由 {@code sa-token-redis-template} 提供），
 *       多实例部署下天然共享，不需要自己维护一份缓存；</li>
 *   <li>它的生命周期与登录态绑定，用户注销时自动清理，不会残留脏数据；</li>
 *   <li>按 loginId（而非 token）维度存储，同一管理员的多个终端共享一份，避免重复计算。</li>
 * </ul>
 *
 * <p><b>代价与应对</b>：缓存带来「改了权限不立刻生效」的问题。
 * 应对方式是：角色/权限发生变更时，调用 {@link #evictCache(Long)} 主动失效，
 * 下次鉴权时重新查库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private static final String SESSION_KEY_ROLES = "nqx:session:roles";
    private static final String SESSION_KEY_PERMISSIONS = "nqx:session:permissions";

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;

    /**
     * 返回该用户拥有的全部权限码。
     *
     * <p>{@code loginType} 必须判断：小程序端没有 RBAC 模型，
     * 若这里不拦截，小程序用户的 loginId（app_user.id）会被拿去查 sys_user 的权限，
     * 而两者的 ID 空间是重叠的 —— 那将是一个严重的越权漏洞。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (!StpAdminLogic.TYPE.equals(loginType)) {
            return Collections.emptyList();
        }
        SaSession session = StpAdminUtil.stpLogic.getSessionByLoginId(loginId);
        Object cached = session.get(SESSION_KEY_PERMISSIONS);
        if (cached instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> permissions = (List<String>) list;
            return permissions;
        }

        Long userId = toUserId(loginId);
        List<String> permissions = sysPermissionMapper.selectPermCodesByUserId(userId);
        session.set(SESSION_KEY_PERMISSIONS, permissions);
        log.debug("加载管理员权限 | userId={} | 权限数={}", userId, permissions.size());
        return permissions;
    }

    /** 返回该用户拥有的角色标识，如 {@code [SUPER_ADMIN]} */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (!StpAdminLogic.TYPE.equals(loginType)) {
            return Collections.emptyList();
        }
        SaSession session = StpAdminUtil.stpLogic.getSessionByLoginId(loginId);
        Object cached = session.get(SESSION_KEY_ROLES);
        if (cached instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) list;
            return roles;
        }

        Long userId = toUserId(loginId);
        List<String> roles = sysRoleMapper.selectRoleCodesByUserId(userId);
        session.set(SESSION_KEY_ROLES, roles);
        log.debug("加载管理员角色 | userId={} | 角色={}", userId, roles);
        return roles;
    }

    /**
     * 使指定管理员的权限缓存失效。
     *
     * <p>在「修改角色权限」「给管理员改角色」「禁用角色」等操作后调用。
     *
     * <h3>⚠️ 这里踩过一个真实的坑：不能用 {@code session.set(key, null)}</h3>
     *
     * <p>本方法最初写的是 {@code session.set(SESSION_KEY_ROLES, null)}，
     * 注释里还振振有词地解释"置 null 等价于缓存未命中，且避免依赖
     * SaSession 是否提供 delete 方法"。**这个取舍是错的。**
     *
     * <p>SaSession 底层是 {@code ConcurrentHashMap}，而
     * <b>{@code ConcurrentHashMap.put(key, null)} 会直接抛 NullPointerException</b>。
     * 也就是说这个方法只要被调用就必然 500，与缓存逻辑无关。
     *
     * <p>它为什么一直没暴露？因为在此之前**没有任何地方调用它**（死代码）。
     * 等到系统管理模块真正接上它时（新增权限节点后要刷缓存），
     * 一次 {@code POST /admin/system/permission} 就直接 500 了。
     *
     * <p><b>教训</b>：「避免依赖某个 API」这种理由，只有在确认过替代方案可行时
     * 才成立。我当时是**猜** SaSession 可能没有 delete 方法就直接绕开，
     * 而实际上 {@code delete} 一直存在（{@code ConcurrentHashMap.remove}
     * 对不存在的键也安全）。<b>拿不准的 API，花十秒看一眼，比写一段听起来
     * 合理的注释更划算。</b>
     */
    public void evictCache(Long userId) {
        SaSession session = StpAdminUtil.stpLogic.getSessionByLoginId(userId);
        // 用 delete 而不是 set(key, null)：后者在 ConcurrentHashMap 上必抛 NPE
        session.delete(SESSION_KEY_ROLES);
        session.delete(SESSION_KEY_PERMISSIONS);
        log.info("已清除管理员权限缓存 | userId={}", userId);
    }

    /**
     * loginId 安全转换为 Long。
     *
     * <p>不能直接强转 {@code (Long) loginId}：Sa-Token 的会话以 JSON 形式存在 Redis 中，
     * 序列化再反序列化后，原本的 {@code Long 1} 可能变成 {@code Integer 1}
     * （取决于 JSON 库对整数的处理），强转会抛 {@code ClassCastException}。
     * 走 {@code toString()} 再解析是最稳妥的做法。
     */
    private Long toUserId(Object loginId) {
        return Long.valueOf(loginId.toString());
    }
}
