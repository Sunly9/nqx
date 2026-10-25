package com.nqx.pojo.vo.app;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.ProductReview;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 商品评价（小程序端）。
 *
 * <p>字段名严格对齐旧评价列表页的模板取值：
 * <pre>{@code
 * {{item.nickname || '匿名用户'}}
 * {{item.avatar_url || '/assets/default_avatar.png'}}
 * {{item.content}}
 * {{item.images}}          ← 页面按数组用，做 .length 与循环
 * {{s <= item.rating ? 'active' : ''}}
 * }</pre>
 *
 * <p>注意 {@code nickname} 在匿名评价时返回<b>空字符串</b>而不是 "匿名用户" ——
 * 因为页面写的是 {@code item.nickname || '匿名用户'}，
 * 如果后端也返回"匿名用户"，那么展示文案就固化在了后端，
 * 想改成"热心同学"就得改后端。让前端兜底更灵活。
 */
@Data
public class ProductReviewVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 评价人昵称；匿名评价返回空串，由前端兜底展示 */
    private String nickname;

    /** 评价人头像，对应旧字段名 */
    @JsonProperty("avatar_url")
    private String avatarUrl;

    /** 评分 1~5 */
    private Integer rating;

    private String content;

    /** 晒图 URL 数组 */
    private List<String> images;

    /** 是否匿名 0否 1是 */
    @JsonProperty("is_anonymous")
    private Integer isAnonymous;

    /** 商家回复 */
    @JsonProperty("reply_content")
    private String replyContent;

    @JsonProperty("created_at")
    private LocalDateTime createTime;

    /**
     * @param nickname  评价人昵称，匿名时传空串
     * @param avatarUrl 评价人头像，匿名时传空串
     */
    public static ProductReviewVO from(ProductReview review, String nickname, String avatarUrl) {
        ProductReviewVO vo = new ProductReviewVO();
        vo.setId(review.getId());
        vo.setUserId(review.getUserId());
        boolean anonymous = Integer.valueOf(1).equals(review.getIsAnonymous());
        vo.setNickname(anonymous ? "" : nickname);
        vo.setAvatarUrl(anonymous ? "" : avatarUrl);
        vo.setRating(review.getRating());
        vo.setContent(review.getContent());
        vo.setImages(parseImages(review.getImages()));
        vo.setIsAnonymous(review.getIsAnonymous());
        vo.setReplyContent(review.getReplyContent());
        vo.setCreateTime(review.getCreateTime());
        return vo;
    }

    /**
     * 把 JSON 字符串解析成数组。
     *
     * <p>解析失败时返回空列表而不是抛异常：一条脏数据不该让整个评价列表打不开。
     * 页面已经做了 {@code item.images && item.images.length > 0} 判断，
     * 给空数组是安全的。
     */
    private static List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> list = JSONUtil.toList(imagesJson, String.class);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
