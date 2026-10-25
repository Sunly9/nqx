package com.nqx.pojo.vo.app;

import cn.hutool.json.JSONUtil;
import com.nqx.pojo.entity.Product;
import com.nqx.pojo.vo.product.ProductDetailVO;
import com.nqx.pojo.vo.product.ProductVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 商品（小程序端）。
 *
 * <h2>为什么不能直接复用管理端的 ProductVO</h2>
 *
 * <p>有两处不可调和的差异：
 *
 * <p><b>① 金额单位不同。</b>旧库 {@code procurement_products.price} 是
 * {@code DECIMAL} 存「元」，页面上直接写 {@code {{item.price}}}；
 * 新库存的是「分」（INT）。若共用同一个 VO、把 {@code price} 输出成分，
 * 商城页面的价格会整体放大 100 倍（12.00 元显示成 1200）。
 *
 * <p>管理后台是全新的 Vue 前端，可以按「分」来设计；小程序端必须迁就旧契约。
 * <b>同一个字段名在两端的语义不同时，就不该共用同一个类</b> ——
 * 硬凑的结果是两边都容易出错。
 *
 * <p><b>② 字段名不同。</b>旧页面读 {@code image} / {@code sales} /
 * {@code points} / {@code sold}，新实体叫 {@code coverUrl} / {@code soldCount} /
 * {@code pointsPrice}。这里做成<b>兼容超集</b>：新旧字段名同时给出，
 * 页面不用改，新写的代码也能用清晰的命名。
 *
 * <p>这与 {@code PointsRecordVO} 的做法一致：<b>多给几个字段不会让旧页面出错，
 * 但少一个就会显示空白。</b>
 */
@Data
public class ProductAppVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long categoryId;

    private String categoryName;

    private String name;

    private String subTitle;

    private String spec;

    /** 封面图，旧字段名（页面读 {@code item.image}） */
    private String image;

    /** 封面图，新字段名 */
    private String coverUrl;

    private Integer productType;

    /**
     * 价格，单位<b>元</b>，保留两位小数。
     *
     * <p>这是旧契约的语义。新库按「分」存储（见 {@code Product#price}），
     * 在这里除以 100 转回来。
     *
     * <p>用 {@link BigDecimal} 而不是 double：金额绝不能用浮点，
     * 0.1 + 0.2 != 0.3 这类误差在价格上是事故。
     */
    private BigDecimal price;

    /** 积分价，旧字段名（积分商城页读 {@code item.points}） */
    private Integer points;

    /** 积分价，新字段名 */
    private Integer pointsPrice;

    private String unit;

    private Integer stock;

    /** 已兑换/已售数量，旧字段名（页面读 {@code item.sales} 与 {@code item.sold}） */
    private Integer sales;

    /** 同上，另一个旧字段名。两个都给是因为商城用 sales、积分商城用 sold */
    private Integer sold;

    private Integer soldCount;

    private Integer exchangeLimit;

    private Integer isHot;

    private Integer status;

    /**
     * 商品描述。
     *
     * <p>旧页面读 {@code product.description}。新库没有同名的富文本列，
     * 这里用 {@code subTitle}（副标题）兜底 —— 至少不是空白。
     * 若将来要做完整详情，应新增 {@code detail} 列并在实体上映射，
     * 而不是在这里硬塞。
     */
    private String description;

    /**
     * 图片集（详情页顶部的轮播）。
     *
     * <p>详情页写的是 {@code product.gallery && product.gallery.length > 0}，
     * 然后用 {@code wx.previewImage} 预览。
     *
     * <p><b>这里原来有个 bug</b>：一直只用封面图拼一个单元素数组
     * （{@code Collections.singletonList(coverUrl)}），
     * 而 {@code product.images} 这一列其实一直存在、管理后台也早就能填。
     * 结果是**多图轮播从来没生效过** —— 因为之前所有商品都只有一张封面图，
     * 这个问题一直没暴露出来。
     *
     * <p>现在：{@code images} 有内容就用它；没有才退回封面图。
     */
    private List<String> gallery;

    private LocalDateTime createTime;

    public static ProductAppVO from(Product product) {
        return from(product, null);
    }    public static ProductAppVO from(Product product, String categoryName) {
        ProductAppVO vo = new ProductAppVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setName(product.getName());
        vo.setSubTitle(product.getSubTitle());
        vo.setSpec(product.getSpec());
        vo.setImage(product.getCoverUrl());
        vo.setCoverUrl(product.getCoverUrl());
        vo.setProductType(product.getProductType());
        vo.setPrice(toYuan(product.getPrice()));
        vo.setPoints(product.getPointsPrice());
        vo.setPointsPrice(product.getPointsPrice());
        vo.setUnit(product.getUnit());
        vo.setStock(product.getStock());
        vo.setSales(product.getSoldCount());
        vo.setSold(product.getSoldCount());
        vo.setSoldCount(product.getSoldCount());
        vo.setExchangeLimit(product.getExchangeLimit());
        vo.setIsHot(product.getIsHot());
        vo.setStatus(product.getStatus());
        // 描述：优先用真正的 description 列；为空才退回副标题。
        // 原来写死 setDescription(getSubTitle()) —— 管理员填的商品描述在小程序里永远看不到。
        vo.setDescription(hasText(product.getDescription())
                ? product.getDescription()
                : product.getSubTitle());
        vo.setGallery(buildGallery(product.getImages(), product.getCoverUrl()));
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }

    /**
     * 由管理端 VO 转换而来。
     *
     * <p>Service 层返回的是 {@code ProductVO}（管理端语义：金额单位为分、
     * 字段名用新命名）。这里做一次转换而不是让 Service 直接产出两种 VO ——
     * 查询逻辑只有一份，展示形态按端区分，职责更清楚。
     */
    public static ProductAppVO from(ProductVO source) {
        ProductAppVO vo = new ProductAppVO();
        vo.setId(source.getId());
        vo.setCategoryId(source.getCategoryId());
        vo.setCategoryName(source.getCategoryName());
        vo.setName(source.getName());
        vo.setSubTitle(source.getSubTitle());
        vo.setSpec(source.getSpec());
        vo.setImage(source.getCoverUrl());
        vo.setCoverUrl(source.getCoverUrl());
        vo.setProductType(source.getProductType());
        vo.setPrice(toYuan(source.getPrice()));
        vo.setPoints(source.getPointsPrice());
        vo.setPointsPrice(source.getPointsPrice());
        vo.setUnit(source.getUnit());
        vo.setStock(source.getStock());
        vo.setSales(source.getSoldCount());
        vo.setSold(source.getSoldCount());
        vo.setSoldCount(source.getSoldCount());
        vo.setExchangeLimit(source.getExchangeLimit());
        vo.setIsHot(source.getIsHot());
        vo.setStatus(source.getStatus());
        // 描述：ProductVO 现在带了 description（管理端列表也用它回填编辑表单）
        vo.setDescription(hasText(source.getDescription())
                ? source.getDescription()
                : source.getSubTitle());
        // ProductVO 的 imagesRaw 是库里的 JSON 字符串原样（见该类注释），在这里解析
        vo.setGallery(buildGallery(source.getImagesRaw(), source.getCoverUrl()));
        vo.setCreateTime(source.getCreateTime());
        return vo;
    }

    /**
     * 由商品详情 VO 转换。
     *
     * <h3>为什么必须单独一个重载（否则轮播图会永远只有一张）</h3>
     *
     * <p>{@link ProductDetailVO} 继承自 {@link ProductVO}，但它把图片**解析成了
     * {@code List<String> images}**，而父类那个 {@code imagesRaw}（原始 JSON 字符串）
     * 是 <b>null</b>。
     *
     * <p>如果没有这个方法，`ProductAppVO.from(productService.detail(id))`
     * 会在编译期命中 `from(ProductVO)`（子类可以向父类隐式转换），
     * 于是 {@code buildGallery(null, coverUrl)} 一路退回"只有封面图"。
     *
     * <p>表现就是：数据库里 3 张轮播图，详情接口只返回 1 张，
     * 而且**编译通过、接口 200、没有任何报错**。这个问题一直到
     * 有人真的填了多图才暴露 —— 之前所有商品都只有一张封面，所以看不出来。
     *
     * <p>修法不是在 {@code from(ProductVO)} 里写 {@code instanceof}，
     * 而是加这个更具体的重载：Java 的重载解析会优先选参数类型最贴近的，
     * 编译期就定下来，比运行时判断更清楚也更难写错。
     */
    public static ProductAppVO from(ProductDetailVO detail) {
        // 先走父类转换，覆盖所有通用字段
        ProductAppVO vo = from((ProductVO) detail);
        // 再用子类已解析好的 images 覆盖 gallery
        vo.setGallery(buildGalleryFromList(detail.getImages(), detail.getCoverUrl()));
        return vo;
    }

    /**
     * 组装轮播图列表。
     *
     * <p>规则刻意保持简单：
     * <ul>
     *   <li>{@code imagesJson} 能解析出非空数组 → 用它（详情页该展示运营挑的那几张）；</li>
     *   <li>否则退回封面图单元素数组（让轮播还能渲染出一张图，而不是整块消失）。</li>
     * </ul>
     *
     * <p><b>不自动把封面塞进 images 的开头</b>：那样运营删掉封面后
     * 会发现轮播里还多一张，行为不可预期。让两件事各自独立更好理解 ——
     * 管理后台提供了「把封面加进来」的按钮，需要时点一下即可。
     */
    private static List<String> buildGallery(String imagesJson, String coverUrl) {
        return buildGalleryFromList(parseImagesJson(imagesJson), coverUrl);
    }

    /** 从已解析好的图片数组组装 gallery；为空则退回封面图 */
    private static List<String> buildGalleryFromList(List<String> images, String coverUrl) {
        if (images != null) {
            // 过滤空串：库里可能有 "[]" 或 [""] 这类残留
            List<String> cleaned = images.stream()
                    .filter(ProductAppVO::hasText)
                    .toList();
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return hasText(coverUrl) ? Collections.singletonList(coverUrl) : Collections.emptyList();
    }

    /**
     * 解析库里的 images JSON。
     *
     * <p>解析失败按空处理：库里存的是 JSON，理论上不会坏，
     * 但真坏了也不该让整个商品详情接口 500 —— 少几张图比打不开页面好。
     */
    private static List<String> parseImagesJson(String imagesJson) {
        if (!hasText(imagesJson)) {
            return null;
        }
        try {
            return JSONUtil.toList(imagesJson, String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 分 → 元。
     *
     * <p>用 {@code movePointLeft(2)} 而不是 {@code divide(100)}：
     * 前者的语义就是"小数点左移两位"，不涉及除法精度问题，
     * 也不需要指定 scale 与舍入模式，意图更清楚。
     */
    private static BigDecimal toYuan(Integer cents) {
        if (cents == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(cents).movePointLeft(2);
    }
}
