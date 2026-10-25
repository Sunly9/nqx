package com.nqx.pojo.vo.product;

import cn.hutool.json.JSONUtil;
import com.nqx.pojo.entity.Product;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.util.Collections;
import java.util.List;

/**
 * 商品详情。
 *
 * <p>在 {@link ProductVO} 的基础上补充详情页才需要的字段：
 * {@code images}（轮播图数组）与 {@code description}（详情长文本）。
 *
 * <p>用继承而非重复声明，是为了让「列表字段」只有一处定义 ——
 * 若商品新增一个公共字段，只需改父类，列表与详情同时生效。
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductDetailVO extends ProductVO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 轮播图 URL 列表 */
    private List<String> images;

    /*
     * description 已上移到父类 ProductVO —— 因为管理端的「编辑商品」弹窗
     * 是拿**列表**行回填的，父类不给这个字段就会在保存时把原描述清空。
     * 这里不再重复声明，避免同一字段在两个层级各有一份定义。
     */

    /**
     * 由实体构建详情 VO。
     *
     * @param product      商品实体
     * @param categoryName 分类名称，由调用方查好后传入（避免在 VO 里再注入 Mapper 造成分层倒置）
     */
    public static ProductDetailVO from(Product product, String categoryName) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setName(product.getName());
        vo.setSubTitle(product.getSubTitle());
        vo.setSpec(product.getSpec());
        vo.setCoverUrl(product.getCoverUrl());
        vo.setProductType(product.getProductType());
        vo.setPrice(product.getPrice());
        vo.setPointsPrice(product.getPointsPrice());
        vo.setUnit(product.getUnit());
        vo.setStock(product.getStock());
        vo.setSoldCount(product.getSoldCount());
        vo.setExchangeLimit(product.getExchangeLimit());
        vo.setIsHot(product.getIsHot());
        vo.setStatus(product.getStatus());
        vo.setSort(product.getSort());
        vo.setCreateTime(product.getCreateTime());
        vo.setDescription(product.getDescription());
        vo.setImages(parseImages(product.getImages()));
        return vo;
    }

    /**
     * 把数据库里的 JSON 数组字符串解析成列表。
     *
     * <p><b>为什么要 try-catch？</b>
     * 历史数据里可能存入非法的 JSON（早期版本用逗号拼接、人工改库等）。
     * 解析失败时若直接抛异常，**一个商品的脏数据会让整个详情接口 500**。
     * 更好的做法是记一条告警日志并返回空列表 —— 让页面能正常打开，
     * 同时留下可排查的线索。
     */
    private static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.toList(json, String.class);
        } catch (Exception e) {
            log.warn("商品轮播图 JSON 解析失败，已降级为空列表 | json={}", json, e);
            return Collections.emptyList();
        }
    }
}
