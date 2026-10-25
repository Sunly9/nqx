package com.nqx.pojo.vo.product;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品列表项。
 *
 * <p><b>为什么不直接返回 {@code Product} 实体？</b>
 * <ul>
 *   <li>实体带 {@code deleted}、{@code images}（JSON 大字段）、{@code description}（TEXT）等，
 *       列表页一次性返回几十条会把响应体撑得很大，而前端一个都用不上；</li>
 *   <li>本 VO 多出一个 {@code categoryName}，来自与 {@code product_category} 的关联查询，
 *       实体里没有这个字段。让前端自己拿 categoryId 再去查分类名，会多出 N 次请求。</li>
 * </ul>
 *
 * <p>注意：这里**刻意不含** {@code images} 字段。列表页不需要轮播图，
 * 而它是 JSON 数组，MyBatis 无法自动映射到 {@code List<String>}。
 * 把大字段和需要手工转换的字段留给详情接口，是列表接口的常见做法。
 */
@Data
public class ProductVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long categoryId;

    /** 分类名称，来自 JOIN 查询 */
    private String categoryName;

    private String name;

    private String subTitle;

    /** 规格，如 CR2032 3V */
    private String spec;

    private String coverUrl;

    /**
     * 商品详情（长文本）。
     *
     * <p><b>加上这两个字段是为了修一个数据丢失 bug。</b>
     *
     * <p>原来列表 VO 里没有它们，而管理端的「编辑商品」弹窗是拿列表行回填的：
     * <pre>{@code
     * form.description = ''                     // 列表没给，只能给空字符串
     * http.put('/admin/product', { ...form })    // 把空字符串提交回去
     * }</pre>
     * 结果：管理员只想改个价格，一保存就把这件商品的<b>描述清空了</b>，
     * 而且没有任何提示、无法撤销。
     *
     * <p>这与 {@code docs/06-踩坑记录.md} 第 26/27 条是同一类问题：
     * <b>读接口少给的字段，最终会以"写接口覆盖"的形式造成数据损坏。</b>
     * 在"编辑"这个场景下，列表 VO 只给展示所需字段这个优化是不成立的。
     */
    private String description;

    /**
     * 多图（**原始 JSON 数组字符串**，数据库列名 {@code images}）。
     *
     * <h3>为什么叫 imagesRaw 而不是 images</h3>
     *
     * <p>子类 {@link ProductDetailVO} 已经把 {@code images} 定义成
     * {@code List<String>}（详情页要直接遍历轮播图）。而列表这边拿到的是
     * 数据库里的 JSON 字符串，Java 字段类型是 {@code String} ——
     * 同名字段在继承体系里类型不一致，编译直接失败。
     *
     * <p>所以这里用 {@code imagesRaw} 承载"原始字符串"，
     * 名字本身就说明了"这是未经解析的"。前端需要数组时自己解析
     * （见 {@code views/product/list/index.vue} 的 {@code parseImageList}）。
     *
     * <p>换成 {@code List<String>} + MyBatis TypeHandler 也可以，
     * 但那要额外引入一个 handler 并影响详情 VO 的现有解析逻辑，
     * 为了一处回填不值得。**在这个位置上，明确的命名比类型优雅更重要。**
     */
    private String imagesRaw;

    /** 类型 1采购商品 2积分兑换商品 3两者皆可 */
    private Integer productType;

    /** 单价（分） */
    private Integer price;

    /** 积分价 */
    private Integer pointsPrice;

    private String unit;

    private Integer stock;

    private Integer soldCount;

    private Integer exchangeLimit;

    private Integer isHot;

    /** 状态 0下架 1上架 */
    private Integer status;

    private Integer sort;

    private LocalDateTime createTime;
}
