package com.nqx.pojo.vo.system;

import com.nqx.pojo.entity.SysPermission;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限菜单树节点。
 *
 * <p><b>为什么不复用现成的 {@link MenuVO} + {@code MenuTreeBuilder}？</b>
 * 那两个类解决的是另一个问题：给侧边栏渲染「当前管理员能看到的目录与菜单」，
 * 因此它们刻意丢掉了按钮节点、隐藏菜单与 status，字段名也换成了路由框架惯用的
 * {@code name} / {@code type}。
 * 而权限管理页面要的恰恰相反 —— 需要完整的树（含按钮、含隐藏、含禁用），
 * 并且要显示 {@code permName} / {@code permType} 这类配置项供编辑。
 * 两者的字段集与过滤规则都不同，硬要复用只会让 {@code MenuVO} 长出
 * 一堆「只在权限管理页才有值」的字段，反而更难维护。
 */
@Data
public class SysPermissionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 父级 ID，0 为根 */
    private Long parentId;

    private String permName;

    private String permCode;

    /** 类型 1目录 2菜单 3按钮 */
    private Integer permType;

    private String path;

    private String component;

    private String icon;

    private Integer sort;

    /** 是否显示 0隐藏 1显示 */
    private Integer visible;

    /** 状态 0禁用 1启用 */
    private Integer status;

    private List<SysPermissionVO> children;

    public static SysPermissionVO from(SysPermission p) {
        SysPermissionVO vo = new SysPermissionVO();
        vo.setId(p.getId());
        vo.setParentId(p.getParentId());
        vo.setPermName(p.getPermName());
        vo.setPermCode(p.getPermCode());
        vo.setPermType(p.getPermType());
        vo.setPath(p.getPath());
        vo.setComponent(p.getComponent());
        vo.setIcon(p.getIcon());
        vo.setSort(p.getSort());
        vo.setVisible(p.getVisible());
        vo.setStatus(p.getStatus());
        return vo;
    }

    /** 追加子节点，惰性初始化，避免调用方到处判空 */
    public void addChild(SysPermissionVO child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }
}
