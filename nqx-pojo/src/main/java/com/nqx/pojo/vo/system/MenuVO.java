package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysPermission;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 前端动态菜单节点。
 *
 * <p><b>为什么不直接把 {@code SysPermission} 实体返回给前端？</b>
 * <ul>
 *   <li>实体带有 {@code deleted}、{@code createTime}、{@code updateTime} 等
 *       前端完全用不到的字段，白白增大响应体；</li>
 *   <li>字段名 {@code permName}、{@code permType} 与前端路由框架惯用的
 *       {@code name}、{@code type} 不一致，前端需要额外做一层映射；</li>
 *   <li>更根本的是：实体结构一旦随数据库调整，前端契约就会被连带破坏。</li>
 * </ul>
 * 用独立的 VO 隔离数据库结构与前端契约，是"防腐层"的基本要求。
 */
@Data
public class MenuVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 权限节点 ID */
    private Long id;

    /** 父节点 ID，0 表示根 */
    private Long parentId;

    /** 菜单名称（前端侧边栏显示的文字） */
    private String name;

    /** 权限标识，如 {@code order:recycle:list} */
    private String permCode;

    /** 节点类型 1目录 2菜单 */
    private Integer type;

    /** 前端路由地址 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 图标名 */
    private String icon;

    /** 排序号 */
    private Integer sort;

    /** 子节点 */
    private List<MenuVO> children;

    public static MenuVO from(SysPermission p) {
        MenuVO vo = new MenuVO();
        vo.setId(p.getId());
        vo.setParentId(p.getParentId());
        vo.setName(p.getPermName());
        vo.setPermCode(p.getPermCode());
        vo.setType(p.getPermType());
        vo.setPath(p.getPath());
        vo.setComponent(p.getComponent());
        vo.setIcon(p.getIcon());
        vo.setSort(p.getSort());
        return vo;
    }

    /** 追加子节点，自动惰性初始化 children，避免调用方到处判空 */
    public void addChild(MenuVO child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }
}
