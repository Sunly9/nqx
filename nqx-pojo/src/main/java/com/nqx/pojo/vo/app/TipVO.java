package com.nqx.pojo.vo.app;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 每日小贴士（随机一条）。
 *
 * <p>只要 {@code content} 一个字段 —— 旧版后端就是
 * {@code SELECT content FROM daily_tips ORDER BY RAND() LIMIT 1}，
 * 前端读的是 {@code res.data.data.content}。
 * VO 里多带字段不会出错，但会让人误以为前端能用上，不如保持最小。
 */
@Data
public class TipVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String content;

    public static TipVO of(String content) {
        TipVO vo = new TipVO();
        vo.setContent(content);
        return vo;
    }
}
