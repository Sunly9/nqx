package com.nqx.pojo.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 权限（目录/菜单/按钮）新增 / 修改入参。
 *
 * <p>字段与 {@code sys_permission} 表一一对应，不做任何裁剪 ——
 * 权限菜单的维护页面本来就是一个「树形表格 + 表单」的后台配置界面，
 * 配置项需要全部可编辑，这与面向普通用户、必须裁剪字段的 VO 是两种不同场景。
 */
@Data
public class SysPermissionSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，新增时为空 */
    private Long id;

    /** 父级 ID，0 表示根节点 */
    @NotNull(message = "请选择上级节点")
    private Long parentId;

    @NotBlank(message = "请输入权限名称")
    @Size(max = 50, message = "权限名称不能超过 50 个字符")
    private String permName;

    /**
     * 权限标识，如 {@code order:recycle:audit}。
     *
     * <p>目录节点可以没有标识（只用于分组），但菜单与按钮必须有 ——
     * 这条跨字段约束在 Service 里校验，因为 jakarta.validation
     * 无法表达「当 permType 为 2 或 3 时该字段必填」。
     */
    @Size(max = 100, message = "权限标识不能超过 100 个字符")
    private String permCode;

    /** 类型 1目录 2菜单 3按钮 */
    @NotNull(message = "请选择权限类型")
    private Integer permType;

    /** 前端路由地址，菜单类型必填 */
    @Size(max = 200, message = "路由地址不能超过 200 个字符")
    private String path;

    /** 前端组件路径，菜单类型必填 */
    @Size(max = 200, message = "组件路径不能超过 200 个字符")
    private String component;

    @Size(max = 100, message = "图标名不能超过 100 个字符")
    private String icon;

    private Integer sort;

    /** 是否显示 0隐藏 1显示 */
    private Integer visible;

    /** 状态 0禁用 1启用 */
    private Integer status;
}
