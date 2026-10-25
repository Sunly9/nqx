package com.nqx.service.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.SysPermissionMapper;
import com.nqx.mapper.SysRolePermissionMapper;
import com.nqx.mapper.SysUserMapper;
import com.nqx.pojo.dto.system.SysPermissionSaveDTO;
import com.nqx.pojo.entity.SysPermission;
import com.nqx.pojo.entity.SysRolePermission;
import com.nqx.pojo.entity.SysUser;
import com.nqx.pojo.vo.system.SysPermissionVO;
import com.nqx.service.satoken.StpAdminUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 权限菜单服务（目录 / 菜单 / 按钮 三合一树）。
 *
 * <p>这张表是本项目 RBAC 的「权限字典」：{@code perm_code} 被
 * {@code @SaCheckPermission} 与前端 {@code v-permission} 按字面量引用，
 * {@code path} / {@code component} 决定前端动态路由，{@code perm_type} 决定菜单如何渲染。
 * 因此本类的校验重点是「别让管理员配出一棵前端渲染不出来的树」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPermissionService {

    /** 类型常量：1目录 2菜单 3按钮 */
    private static final int TYPE_DIRECTORY = 1;
    private static final int TYPE_MENU = 2;
    private static final int TYPE_BUTTON = 3;

    /** 父节点 ID 为 0 表示根节点 */
    private static final long ROOT_PARENT_ID = 0L;

    private final SysPermissionMapper sysPermissionMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysUserMapper sysUserMapper;
    private final AdminPermissionCacheEvictor permissionCacheEvictor;

    // ==================== 查询 ====================

    /**
     * 完整权限树。
     *
     * <p>注意这里返回的是<b>全部</b>节点（含按钮、含隐藏、含禁用、含逻辑删除=0 的全部），
     * 与登录时下发菜单用的 {@code selectMenusByUserId}（只给目录与菜单、过滤 visible 与 status）
     * 是两个完全不同的用途：管理页面必须能看到并编辑所有节点，
     * 否则一个被禁用的节点就会从界面上消失，从此再也无法启用回来。
     */
    public List<SysPermissionVO> tree() {
        List<SysPermission> permissions = sysPermissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery()
                        .orderByAsc(SysPermission::getParentId)
                        .orderByAsc(SysPermission::getSort)
                        .orderByAsc(SysPermission::getId));
        return buildTree(permissions);
    }

    // ==================== 增删改 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPermissionSaveDTO dto) {
        validateType(dto.getPermType());
        checkParent(dto.getParentId(), dto.getPermType());
        checkFieldsByType(dto);
        checkPermCodeUnique(dto.getPermCode(), null);

        SysPermission permission = new SysPermission();
        applyDto(permission, dto);
        permission.setDeleted(CommonConstants.DELETED_NO);
        sysPermissionMapper.insert(permission);

        // 新增节点本身不会改变任何人的权限码，但还是统一清一次：
        // 「凡是对权限字典的写操作都失效缓存」这条规则保持整齐，
        // 将来若在新增时补上「自动授予超管角色」之类的逻辑，也不会因为忘了清缓存而出问题
        evictAllAdminPermissionCache();
        log.info("新增权限节点 | id={} | name={} | type={} | 操作人={}",
                permission.getId(), dto.getPermName(), dto.getPermType(), StpAdminUtil.getLoginIdDefaultNull());
        return permission.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysPermissionSaveDTO dto) {
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        SysPermission exists = sysPermissionMapper.selectById(dto.getId());
        if (exists == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "权限节点不存在或已被删除");
        }

        validateType(dto.getPermType());
        checkParent(dto.getParentId(), dto.getPermType());
        checkMoveTarget(dto.getId(), dto.getParentId());
        checkFieldsByType(dto);
        checkPermCodeUnique(dto.getPermCode(), dto.getId());

        // 有子节点的节点不允许改类型：目录下面的子节点是按「父是目录」配置的，
        // 一旦把父改成菜单或按钮，子树的结构约束就被破坏了，
        // 而这些子节点在前端会直接渲染不出来
        if (dto.getPermType() != exists.getPermType() && hasChildren(dto.getId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "该节点下存在子节点，不能修改权限类型");
        }

        SysPermission permission = new SysPermission();
        permission.setId(dto.getId());
        applyDto(permission, dto);
        // MyBatis-Plus 默认跳过 null 字段，permCode 允许为空（目录节点），
        // 因此这里显式补一个空串表达"清空"，否则取消填写后旧标识会一直留着
        if (!StringUtils.hasText(dto.getPermCode())) {
            permission.setPermCode("");
        }
        sysPermissionMapper.updateById(permission);

        evictAllAdminPermissionCache();
        log.info("修改权限节点 | id={} | name={} | 操作人={}",
                dto.getId(), dto.getPermName(), StpAdminUtil.getLoginIdDefaultNull());
    }

    /**
     * 删除权限节点。
     *
     * <p>三条处理全是必须的：
     * <ol>
     *   <li><b>有子节点则拒绝。</b>连带删除整棵子树看起来"更好用"，
     *       但管理员往往只看到树的一角（比如折叠着），一次点击删掉的节点数会远超预期，
     *       而且删掉的按钮权限会静默地让若干账号失去功能。要求先删子节点，
     *       是把「影响范围」交回给操作者确认；</li>
     *   <li><b>清理 {@code sys_role_permission}。</b>关联表没有逻辑删除列，
     *       不清理这些行就是孤儿数据：它们不再对应任何权限，却会让
     *       「角色已勾选的权限」出现幽灵条目；</li>
     *   <li><b>清空所有管理员的权限缓存。</b>权限字典变了，
     *       所有人的权限码集合都可能变，缓存的粒度是「按人」的，只能逐个失效。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (sysPermissionMapper.selectById(id) == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "权限节点不存在或已被删除");
        }

        if (hasChildren(id)) {
            throw new BizException(ResultCode.PARAM_ERROR, "该权限下存在子节点，请先删除子节点");
        }

        sysPermissionMapper.deleteById(id);
        sysRolePermissionMapper.delete(
                Wrappers.<SysRolePermission>lambdaQuery().eq(SysRolePermission::getPermissionId, id));

        evictAllAdminPermissionCache();
        log.info("删除权限节点 | id={} | 操作人={}", id, StpAdminUtil.getLoginIdDefaultNull());
    }

    // ==================== 私有方法 ====================

    /**
     * 扁平列表 → 树，时间复杂度 O(n)。
     *
     * <p>与 {@code MenuTreeBuilder} 用同一套「一次建索引 + 一次挂父子」的思路，
     * 但没有复用它：这里要保留按钮节点与 status 等字段，输出的类型也不同。
     * 两次遍历而不是对每个节点递归找子节点（那是 O(n²)，且树深了会栈溢出）。
     *
     * <p>父节点找不到时把子节点提升为根，而不是静默丢弃 —— 例如父节点被
     * 手工从数据库删掉（绕过接口）时，子节点仍应可见可修，
     * 否则界面上会凭空少掉一批节点，排查起来毫无线索。
     */
    private List<SysPermissionVO> buildTree(List<SysPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return new ArrayList<>();
        }
        // 入参已按 parent_id, sort 排序，用 LinkedHashMap 保持这个顺序，
        // 从而让同一层级内按 sort 稳定排列
        Map<Long, SysPermissionVO> nodeMap = new LinkedHashMap<>(permissions.size() * 2);
        for (SysPermission permission : permissions) {
            nodeMap.put(permission.getId(), SysPermissionVO.from(permission));
        }

        List<SysPermissionVO> roots = new ArrayList<>();
        for (SysPermissionVO node : nodeMap.values()) {
            SysPermissionVO parent = node.getParentId() == null ? null : nodeMap.get(node.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.addChild(node);
            }
        }

        // 数据库的排序是「父 ID 升序」，同一父节点下的子节点虽然按 sort 有序，
        // 但挂到父节点上是按全局顺序追加的，这里再兜一次底按 sort 排序，
        // 避免排序规则将来被改动时菜单顺序错乱
        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<SysPermissionVO> nodes) {
        nodes.sort(Comparator.comparing(SysPermissionVO::getSort,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SysPermissionVO::getId));
        for (SysPermissionVO node : nodes) {
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                sortRecursively(node.getChildren());
            }
        }
    }

    private boolean hasChildren(Long id) {
        Long count = sysPermissionMapper.selectCount(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getParentId, id));
        return count != null && count > 0;
    }

    private void validateType(Integer permType) {
        if (permType == null
                || (permType != TYPE_DIRECTORY && permType != TYPE_MENU && permType != TYPE_BUTTON)) {
            throw new BizException(ResultCode.PARAM_ERROR, "权限类型只能是 1（目录）、2（菜单）或 3（按钮）");
        }
    }

    /**
     * 校验父节点存在且层级合法。
     *
     * <p>层级约束（目录 → 菜单 → 按钮）不是洁癖：前端 {@code dynamic.ts} 只把
     * 类型 1 当容器、类型 2 当页面，类型 2 挂到类型 2 下面时子节点永远不会被渲染；
     * 按钮挂在目录下则会在菜单树里丢失父级。让这些配置在保存时就失败，
     * 好过让管理员配完发现"菜单没出来"却找不到原因。
     */
    private void checkParent(Long parentId, Integer permType) {
        if (parentId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择上级节点");
        }
        if (parentId == ROOT_PARENT_ID) {
            if (permType != TYPE_DIRECTORY) {
                throw new BizException(ResultCode.PARAM_ERROR, "只有「目录」可以挂在根节点下");
            }
            return;
        }

        SysPermission parent = sysPermissionMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "上级节点不存在或已被删除");
        }
        int parentType = parent.getPermType() == null ? 0 : parent.getPermType();
        if (permType == TYPE_MENU && parentType != TYPE_DIRECTORY) {
            throw new BizException(ResultCode.PARAM_ERROR, "菜单必须挂在「目录」下");
        }
        if (permType == TYPE_BUTTON && parentType != TYPE_MENU) {
            throw new BizException(ResultCode.PARAM_ERROR, "按钮必须挂在「菜单」下");
        }
        if (permType == TYPE_DIRECTORY && parentType != TYPE_DIRECTORY) {
            throw new BizException(ResultCode.PARAM_ERROR, "目录只能挂在另一个目录下");
        }
    }

    /**
     * 防止把节点挂到它自己或它的子孙下面。
     *
     * <p>一旦形成环，这棵子树就会从树上彻底消失（没有任何根节点能到达它），
     * 但数据仍然在库里占着 {@code perm_code}、并继续参与鉴权计算 ——
     * 属于「界面上看不到、运行时却生效」的最难排查的一类脏数据。
     * 因此在写入前先把子孙集合算出来做一次判断。
     */
    private void checkMoveTarget(Long id, Long newParentId) {
        if (newParentId == null || newParentId == ROOT_PARENT_ID) {
            return;
        }
        if (newParentId.equals(id)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能把节点挂到它自己下面");
        }
        // 一次取回全部节点后在内存里向上追溯，比逐层查库更简单，也不会受树深影响
        Map<Long, Long> parentOf = new LinkedHashMap<>();
        for (SysPermission permission : sysPermissionMapper.selectList(null)) {
            parentOf.put(permission.getId(), permission.getParentId());
        }
        Long cursor = newParentId;
        int guard = 0;
        while (cursor != null && cursor != ROOT_PARENT_ID && guard++ <= parentOf.size()) {
            if (cursor.equals(id)) {
                throw new BizException(ResultCode.PARAM_ERROR, "不能把节点移动到它自己的子节点下");
            }
            cursor = parentOf.get(cursor);
        }
    }

    /**
     * 按类型校验必填字段。
     *
     * <p>这类「当 A 字段取某值时 B 字段才必填」的条件约束，jakarta.validation
     * 表达不了（除非用分组校验，那会让 DTO 难读得多），所以放在 Service 里。
     */
    private void checkFieldsByType(SysPermissionSaveDTO dto) {
        if (dto.getPermType() == TYPE_DIRECTORY) {
            return;
        }
        if (!StringUtils.hasText(dto.getPermCode())) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    dto.getPermType() == TYPE_MENU ? "菜单必须填写权限标识" : "按钮必须填写权限标识");
        }
        if (dto.getPermType() == TYPE_MENU) {
            if (!StringUtils.hasText(dto.getPath())) {
                throw new BizException(ResultCode.PARAM_ERROR, "菜单必须填写前端路由地址");
            }
            if (!StringUtils.hasText(dto.getComponent())) {
                // 没有组件的菜单在前端会被替换成"页面不存在"占位页，
                // 与其让管理员点进去看到报错，不如在这里就拦住
                throw new BizException(ResultCode.PARAM_ERROR, "菜单必须填写前端组件路径");
            }
        }
    }

    /** 权限标识唯一性校验（目录节点允许不填标识，因此为空时跳过） */
    private void checkPermCodeUnique(String permCode, Long excludeId) {
        if (!StringUtils.hasText(permCode)) {
            return;
        }
        String code = permCode.trim();
        Long count = sysPermissionMapper.selectCount(
                Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getPermCode, code)
                        .ne(excludeId != null, SysPermission::getId, excludeId));
        if (count != null && count > 0) {
            // 数据库上 perm_code 只是普通索引，撞车不会有任何报错，
            // 而是让两个节点拥有同一个权限码 —— 那意味着「授权 A 就顺带授了 B」，
            // 这种越权不会有任何提示，只能靠业务层拦截
            throw new BizException(ResultCode.PARAM_ERROR, "权限标识「" + code + "」已被其它节点使用");
        }
    }

    private void applyDto(SysPermission permission, SysPermissionSaveDTO dto) {
        permission.setParentId(dto.getParentId());
        permission.setPermName(dto.getPermName());
        permission.setPermCode(StringUtils.hasText(dto.getPermCode()) ? dto.getPermCode().trim() : null);
        permission.setPermType(dto.getPermType());
        permission.setPath(dto.getPath());
        permission.setComponent(dto.getComponent());
        permission.setIcon(dto.getIcon());
        permission.setSort(dto.getSort() == null ? 0 : dto.getSort());
        permission.setVisible(dto.getVisible() == null ? CommonConstants.STATUS_ENABLED : dto.getVisible());
        permission.setStatus(dto.getStatus() == null ? CommonConstants.STATUS_ENABLED : dto.getStatus());
    }

    /**
     * 让全部管理员的权限缓存失效。
     *
     * <p>权限字典的变更会影响每一个人的权限码集合，而缓存是按 loginId 存的，
     * 因此只能遍历所有账号。后台账号数量在几十的量级，逐个调用 Redis 完全可以接受；
     * 若将来账号规模变大，正确做法是给缓存加一个「权限字典版本号」，
     * 而不是在这里偷懒漏清 —— 漏清的后果是权限改了却不生效。
     */
    private void evictAllAdminPermissionCache() {
        List<Long> userIds = sysUserMapper
                .selectList(Wrappers.<SysUser>lambdaQuery().select(SysUser::getId))
                .stream()
                .map(SysUser::getId)
                .filter(Objects::nonNull)
                .toList();
        permissionCacheEvictor.evictAll(userIds);
    }
}
