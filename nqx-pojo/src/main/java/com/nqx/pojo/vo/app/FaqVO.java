package com.nqx.pojo.vo.app;

import com.nqx.pojo.entity.Faq;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 常见问题。
 *
 * <p>前端 {@code help} 页的写法是：
 * <pre>{@code
 * const list = res.data.data.map(item => ({ ...item, open: false }));
 * }</pre>
 * 即它会<b>把整个对象展开</b>使用，因此字段名必须与页面里引用的完全一致
 * （{@code question} / {@code answer}）。多一个 {@code deleted} 之类
 * 无伤大雅，但少一个就会显示空白。
 */
@Data
public class FaqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String question;

    private String answer;

    /** 展示端 1回收端 2采购端 3通用 */
    private Integer faqType;

    private Integer sort;

    public static FaqVO from(Faq faq) {
        FaqVO vo = new FaqVO();
        vo.setId(faq.getId());
        vo.setQuestion(faq.getQuestion());
        vo.setAnswer(faq.getAnswer());
        vo.setFaqType(faq.getFaqType());
        vo.setSort(faq.getSort());
        return vo;
    }
}
