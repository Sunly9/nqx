package com.nqx.service.system;

import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 管理员权限缓存失效器。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>{@link StpInterfaceImpl} 把管理员的角色与权限码缓存在 Sa-Token 的 Account-Session 里，
 * 以避免每次鉴权都做四表 JOIN。代价是：在后台把某人的角色改掉之后，
 * 他手里的旧权限仍然有效，直到缓存被清掉 —— 这是一个真实的越权漏洞
 * （「已经取消了他的删除权限，但他还能删」）。
 *
 * <p>失效动作只有两种触发场景（改人的角色、改角色的权限），但涉及的角色/账号
 * 是一对多关系，散落在三个 Service 里各写一遍「先查谁受影响、再逐个清缓存」
 * 既啰嗦又容易漏。集中到这一个类，让「改了权限就要清缓存」成为一条无法绕过的路径。
 *
 * <h3>为什么这里只是薄薄一层转发</h3>
 *
 * <p>真正的清理逻辑在 {@link StpInterfaceImpl#evictCache}。本类只负责两件它不该管的事：
 * <ol>
 *   <li><b>批量</b>：一个角色被改动时，所有拥有该角色的管理员都要清；</li>
 *   <li><b>跳过未登录账号</b>：缓存只在「某个已登录请求的鉴权过程」中被读取，
 *       给离线账号调 {@code getSessionByLoginId} 反而会在 Redis 里
 *       新建一个永不使用的空会话。</li>
 * </ol>
 *
 * <p>⚠️ 本类早先自己复制了一份 {@code session.delete("nqx:session:roles")} 的实现，
 * 因为当时 {@code evictCache} 是坏的（内部用 {@code session.set(key, null)}，
 * 在 ConcurrentHashMap 上必抛 NPE）。那导致两个字符串常量必须手工保持同步 ——
 * 一旦那边改了键名，这里会<b>静默失效</b>（表现为"改了权限却不生效"，比报错难查得多）。
 * 现在 {@code evictCache} 已修好，这里直接复用它，键名只在
 * {@link StpInterfaceImpl} 里存在一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminPermissionCacheEvictor {

    private final StpInterfaceImpl stpInterfaceImpl;

    /**
     * 失效单个管理员的权限缓存。
     *
     * <p>先判断 {@code isLogin}：未登录的账号既没有生效中的 token、
     * 也没有正在被读取的缓存，跳过即可。
     */
    public void evict(Long userId) {
        if (userId == null || !StpAdminUtil.isLogin(userId)) {
            return;
        }
        stpInterfaceImpl.evictCache(userId);
    }

    /** 批量失效，用于「一个角色被改动 → 所有拥有该角色的管理员都受影响」的场景 */
    public void evictAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        userIds.forEach(this::evict);
    }
}
