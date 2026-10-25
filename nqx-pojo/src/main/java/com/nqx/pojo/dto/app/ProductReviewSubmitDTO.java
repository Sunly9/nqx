package com.nqx.pojo.dto.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 提交商品评价入参。
 *
 * <p>结构对齐旧前端的提交方式：<b>一次提交整单的多条评价</b>。
 * <pre>{@code
 * { order_id: 123,
 *   reviews: [ { product_id, rating, content, images }, ... ] }
 * }</pre>
 *
 * <p>为什么是批量而不是一条一个请求？因为用户是在「订单评价页」上
 * 把一单里的商品挨个评完、点一次"发布"。拆成多个请求会出现
 * "第一条成功、第二条失败"的半完成状态，用户不知道该不该重试。
 * 批量提交可以放进一个事务，要么全成要么全不成。
 */
@Data
public class ProductReviewSubmitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "缺少订单 ID")
    @JsonProperty("order_id")
    private Long orderId;

    @NotEmpty(message = "请至少提交一条评价")
    @Valid
    private List<ReviewItem> reviews;

    /**
     * 单条评价。
     *
     * <p>注意这里<b>不接受</b>前端传来的 {@code product_name} 与 {@code product_image}。
     * 旧版把它们直接入库，等于让客户端决定评价里显示的商品名 ——
     * 正常用户不会去改，但这确实是一个可被利用的注入点。
     * 新版只认 {@code product_id}，名称从订单明细的快照里取。
     */
    @Data
    public static class ReviewItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @NotNull(message = "缺少商品 ID")
        @JsonProperty("product_id")
        private Long productId;

        @NotNull(message = "请给出评分")
        @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星")
        private Integer rating;

        @Size(max = 500, message = "评价内容不能超过 500 字")
        private String content;

        /** 晒图 URL 列表，最多 3 张 */
        @Size(max = 3, message = "最多上传 3 张图片")
        private List<String> images;
    }
}
