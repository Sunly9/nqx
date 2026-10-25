package com.nqx.service.content;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.mapper.AnnouncementMapper;
import com.nqx.mapper.DailyTipMapper;
import com.nqx.mapper.FaqMapper;
import com.nqx.mapper.SiteSettingMapper;
import com.nqx.pojo.entity.Announcement;
import com.nqx.pojo.entity.DailyTip;
import com.nqx.pojo.entity.Faq;
import com.nqx.pojo.entity.SiteSetting;
import com.nqx.pojo.vo.app.AnnouncementVO;
import com.nqx.pojo.vo.app.FaqVO;
import com.nqx.pojo.vo.app.TipVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内容服务：小贴士、常见问题、公告、站点配置。
 *
 * <p>这些数据的共同特点是<b>读极多、写极少、数据量很小</b>，
 * 因此设计上做了针对性取舍：
 * <ul>
 *   <li>不引入 Redis 缓存。数据量只有几十条，查库本身就是毫秒级；
 *       加缓存反而要处理失效问题，收益为负。
 *       真正需要缓存的是商品详情那种"读多、单条数据大、计算有成本"的场景。</li>
 *   <li>随机小贴士不写 {@code ORDER BY RAND()}。该写法在大表上会
 *       全表扫描 + 文件排序，是典型的性能陷阱；这里改成一次全量取出
 *       在内存里随机，几十条数据的开销可以忽略。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    /** 启用状态 */
    private static final int STATUS_ENABLED = 1;

    /** 数据库无数据时的兜底文案，保证前端不会显示空白 */
    private static final String FALLBACK_TIP = "回收纽扣电池，从实验室做起。";

    private final DailyTipMapper dailyTipMapper;
    private final FaqMapper faqMapper;
    private final AnnouncementMapper announcementMapper;
    private final SiteSettingMapper siteSettingMapper;

    /**
     * 随机取一条启用的小贴士。
     *
     * <p><b>为什么不用 {@code ORDER BY RAND() LIMIT 1}？</b>
     * MySQL 执行它时需要为每一行生成一个随机数，然后对全表排序，
     * 复杂度是 O(n log n) 且无法使用索引。10 万行的表上单次查询可能耗时数百毫秒。
     * 本项目贴士只有几条到几十条，一次全量取出在 Java 里随机更简单也更快，
     * 而且避免了数据库方言差异。
     */
    public TipVO randomTip() {
        List<DailyTip> tips = dailyTipMapper.selectList(Wrappers.<DailyTip>lambdaQuery()
                .eq(DailyTip::getStatus, STATUS_ENABLED));
        if (tips.isEmpty()) {
            return TipVO.of(FALLBACK_TIP);
        }
        DailyTip tip = tips.get(ThreadLocalRandom.current().nextInt(tips.size()));
        return TipVO.of(tip.getContent());
    }

    /**
     * 常见问题列表。
     *
     * @param faqType 展示端 1回收端 2采购端；传 null 返回全部
     *                <p>用 {@code faq_type IN (指定值, 3)} 的方式让「通用」问题
     *                在两个端都能出现 —— 旧版把 FAQ 拆成两张表，
     *                通用问题不得不重复录入两遍，改一次要改两处。
     */
    public List<FaqVO> listFaq(Integer faqType) {
        List<Faq> faqs = faqMapper.selectList(Wrappers.<Faq>lambdaQuery()
                .eq(Faq::getStatus, STATUS_ENABLED)
                .and(faqType != null, w -> w.eq(Faq::getFaqType, faqType).or().eq(Faq::getFaqType, 3))
                .orderByAsc(Faq::getSort));
        return faqs.stream().map(FaqVO::from).toList();
    }

    /**
     * 当前生效的公告（取一条）。
     *
     * <p>生效条件：已发布 + 在生效时间窗内。时间窗为空表示不限制。
     * 这套条件比旧版的单一 {@code is_active} 开关强的地方在于：
     * 活动公告可以预先配置好时间，到点自动上线、过期自动下线，
     * 不需要运营在活动开始/结束时各手动点一次（忘了就会挂错时间）。
     */
    public AnnouncementVO activeAnnouncement() {
        LocalDateTime now = LocalDateTime.now();
        Page<Announcement> page = new Page<>(1, 1);
        Page<Announcement> result = announcementMapper.selectPage(page, Wrappers.<Announcement>lambdaQuery()
                .eq(Announcement::getStatus, STATUS_ENABLED)
                .and(w -> w.isNull(Announcement::getStartTime).or().le(Announcement::getStartTime, now))
                .and(w -> w.isNull(Announcement::getEndTime).or().ge(Announcement::getEndTime, now))
                .orderByAsc(Announcement::getSort)
                .orderByDesc(Announcement::getId));
        List<Announcement> records = result.getRecords();
        return records.isEmpty() ? null : AnnouncementVO.from(records.get(0));
    }

    /**
     * 站点配置（KV 全量返回）。
     *
     * <p>配置项只有个位数，一次全量返回比按需逐个接口查询更省事：
     * 前端启动时取一次放进 globalData，后续直接用，减少请求数。
     *
     * <p>用 {@link LinkedHashMap} 保证顺序稳定，便于人工比对。
     */
    public Map<String, String> allSettings() {
        List<SiteSetting> settings = siteSettingMapper.selectList(null);
        if (settings.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>(settings.size() * 2);
        settings.forEach(s -> map.put(s.getSettingKey(), s.getSettingValue()));
        return map;
    }

    /**
     * 读取单个配置项，带默认值。
     *
     * <p>供其它 Service 使用（例如回收结算要读"每公斤积分"兜底单价）。
     * 配置缺失时返回默认值而不是抛异常 —— 少配一个可选项不该让业务不可用。
     */
    public String getSetting(String key, String defaultValue) {
        SiteSetting setting = siteSettingMapper.selectOne(
                Wrappers.<SiteSetting>lambdaQuery().eq(SiteSetting::getSettingKey, key));
        return (setting == null || setting.getSettingValue() == null)
                ? defaultValue : setting.getSettingValue();
    }

    /** 读取数值型配置项 */
    public int getIntSetting(String key, int defaultValue) {
        String value = getSetting(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // 配置被运营填成了非数字：记警告并用默认值，不让整个接口挂掉
            log.warn("站点配置项不是合法整数，已使用默认值 | key={} | value={} | default={}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
