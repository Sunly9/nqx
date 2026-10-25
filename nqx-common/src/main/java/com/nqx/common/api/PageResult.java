package com.nqx.common.api;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 统一分页返回体。
 *
 * <p>本类刻意设计为「不依赖任何持久层框架」的纯 POJO —— 因为 nqx-common 模块
 * 不应该为了一个分页结构就引入 MyBatis-Plus。持久层的 {@code IPage} 到本类的转换
 * 由 nqx-pojo 模块的 {@code PageUtil} 完成。
 *
 * <p>这样做的收益：未来如果换掉持久层框架，前端契约完全不受影响。
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private Long total;

    /** 当前页码，从 1 开始 */
    private Long current;

    /** 每页条数 */
    private Long size;

    /** 总页数 —— 冗余字段，省去前端自己算 */
    private Long pages;

    public PageResult() {
        this.records = Collections.emptyList();
        this.total = 0L;
        this.current = 1L;
        this.size = 10L;
        this.pages = 0L;
    }

    public PageResult(List<T> records, Long total, Long current, Long size) {
        this.records = records == null ? Collections.emptyList() : records;
        this.total = total == null ? 0L : total;
        this.current = current == null ? 1L : current;
        this.size = (size == null || size <= 0) ? 10L : size;
        // 向上取整：(total + size - 1) / size
        this.pages = (this.total + this.size - 1) / this.size;
    }

    /** 空分页，用于「查询结果为空」但不想返回 null 的场景 */
    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(Collections.emptyList(), 0L, current, size);
    }

    /**
     * 把记录逐条转换成另一种类型，保留分页元信息。
     *
     * <p>典型用途：Service 返回的是面向管理端的 {@code PageResult<ProductVO>}，
     * 而小程序端需要另一套字段（金额单位、字段名都不同），
     * 于是用 {@code result.map(ProductAppVO::from)} 就地转换。
     *
     * <p>有了它，各 Controller 就不必重复写
     * "新建 PageResult → setRecords(stream().map()) → setTotal/setCurrent/setSize"
     * 这四行 —— 那种写法每处都要记得把四个元信息字段都拷全，
     * 漏掉 {@code pages} 就会让前端的分页组件显示 0 页。
     */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = this.records == null
                ? Collections.emptyList()
                : this.records.stream().<R>map(mapper).toList();
        return new PageResult<>(mapped, this.total, this.current, this.size);
    }
}
