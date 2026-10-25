package com.nqx.pojo.vo.product;

import com.nqx.pojo.entity.ProductCategory;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品分类节点。
 *
 * <p>与 {@code MenuVO} 同构：同一个 VO 既能表达树（带 children），
 * 也能表达单条分类，避免再维护一个「扁平分类 VO」和一份重复的字段映射。
 *
 * <p>刻意<b>不含</b> {@code createTime} / {@code updateTime} / {@code deleted}：
 * 分类树是给前端做筛选导航用的，时间字段与逻辑删除标记一个都用不上，
 * 返回它们只会白占带宽，也给前端留下不该依赖的字段。
 */
@Data
public class CategoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 父级 ID，0 表示根分类 */
    private Long parentId;

    private String name;

    /** 图标 URL */
    private String icon;

    /** 排序号，同级内升序 */
    private Integer sort;

    /** 状态 0禁用 1启用 */
    private Integer status;

    /**
     * 子分类。
     *
     * <p>叶子节点保持 null 而不是空集合 —— 这一点与 {@code MenuVO} 一致：
     * 空数组会让部分前端树组件渲染出一个可点开却什么都没有的展开箭头。
     */
    private List<CategoryVO> children;

    public static CategoryVO from(ProductCategory c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setIcon(c.getIcon());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        return vo;
    }

    /** 追加子节点，惰性初始化 children，避免调用方到处判空 */
    public void addChild(CategoryVO child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }
}
