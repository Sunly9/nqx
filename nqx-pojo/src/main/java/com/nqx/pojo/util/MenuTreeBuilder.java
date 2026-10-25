package com.nqx.pojo.util;

import com.nqx.pojo.entity.SysPermission;
import com.nqx.pojo.vo.system.MenuVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扁平权限列表 → 树形菜单。
 *
 * <p><b>为什么不用递归？</b>
 * 常见的实现是「对每个节点递归查找其子节点」，时间复杂度 O(n²)：
 * 1000 个节点要比较 100 万次。这里用一次遍历建索引 + 一次遍历挂父子关系，
 * 时间复杂度 <b>O(n)</b>，且不受树深度影响（递归实现深度过大还会栈溢出）。
 *
 * <p>本项目权限节点只有几十个，两种写法性能都能接受，
 * 但这个模式是通用的：凡是"把带 parentId 的扁平列表转成树"的场景
 * （部门树、分类树、评论树）都适用，写成 O(n) 是更好的习惯。
 */
public final class MenuTreeBuilder {

    private MenuTreeBuilder() {
    }

    /**
     * 构建菜单树。
     *
     * @param permissions 扁平的权限列表（需已按 parent_id, sort 排序）
     * @return 树形结构的根节点列表
     */
    public static List<MenuVO> build(List<SysPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return new ArrayList<>();
        }

        // 第一遍：把所有节点转成 VO 并建立 id → VO 的索引。
        // 用 LinkedHashMap 保持传入顺序（即 SQL 的 ORDER BY parent_id, sort 顺序），
        // 保证同一层级内菜单顺序稳定。
        Map<Long, MenuVO> nodeMap = new LinkedHashMap<>(permissions.size() * 2);
        for (SysPermission p : permissions) {
            nodeMap.put(p.getId(), MenuVO.from(p));
        }

        // 第二遍：把每个节点挂到它的父节点上
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO node : nodeMap.values()) {
            MenuVO parent = node.getParentId() == null ? null : nodeMap.get(node.getParentId());
            if (parent == null) {
                // 两种情况会走到这里：
                //   1) parentId = 0，确实是根节点；
                //   2) 父节点不在授权范围内（管理员只被授予了子菜单，没授予父目录）。
                // 第 2 种情况**必须**把子节点提升为根，否则该菜单会被静默丢弃，
                // 管理员配好了权限却发现菜单不显示，排查起来非常困难。
                roots.add(node);
            } else {
                parent.addChild(node);
            }
        }
        return roots;
    }
}
