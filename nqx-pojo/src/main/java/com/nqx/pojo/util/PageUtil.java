package com.nqx.pojo.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.common.api.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * 分页转换工具：MyBatis-Plus 的 {@link IPage} → 前端契约 {@link PageResult}。
 *
 * <p><b>为什么需要这一层转换，而不是直接返回 IPage？</b>
 * {@code IPage} 是持久层对象，它的实现类 {@code com.baomidou.mybatisplus.extension.plugins.pagination.Page}
 * 会随 MyBatis-Plus 版本变化而改变字段结构（例如 {@code searchCount}、{@code optimizeCountSql}、
 * {@code maxLimit} 这些内部字段曾经直接暴露给前端）。
 * 一旦升级 MP 版本，前端的取数路径就可能失效。
 *
 * <p>转换成自定义的 {@code PageResult} 后，<b>前端契约与持久层框架彻底解耦</b>。
 * 这是"防腐层"思想在分页上的具体应用。
 */
public final class PageUtil {

    private PageUtil() {
    }

    /** 直接转换，适用于实体本身就是返回类型的情况 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 带元素转换的分页映射，适用于需要把 Entity 转成 VO 的场景。
     *
     * <pre>{@code
     * IPage<Product> page = productMapper.selectPage(...);
     * return PageUtil.of(page, ProductConverter.INSTANCE::toVO);
     * }</pre>
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> converter) {
        List<V> records = page.getRecords().stream().map(converter).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }
}
