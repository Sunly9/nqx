package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.pojo.vo.app.AnnouncementVO;
import com.nqx.pojo.vo.app.FaqVO;
import com.nqx.pojo.vo.app.TipVO;
import com.nqx.service.content.ContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 小程序端内容接口（小贴士 / FAQ / 公告 / 站点配置）。
 *
 * <p>全部无需登录 —— 这些是进入小程序就要展示的内容，
 * 不能等用户登录后才可见。路径前缀 {@code /app/content/**}
 * 需要在 {@code SaTokenConfig} 的白名单里放行。
 *
 * <p><b>关于返回体的一层额外包装</b>：前端读的是 {@code res.data.data}，
 * 因为旧版后端返回的是 {@code res.json({ data: rows })}，而
 * {@code callContainer} 把整个 body 放在 {@code res.data} 里。
 * 本项目<b>没有</b>为了迁就这一点在后端再套一层 {@code data}，
 * 而是由请求垫片（{@code utils/request.js} 的 {@code DATA_WRAPPED_PATHS}）
 * 负责还原旧结构 —— 兼容旧客户端的脏活应当留在适配层，
 * 不该污染新后端的接口契约。
 */
@Tag(name = "小程序端-内容", description = "每日小贴士、常见问题、公告、站点配置（均无需登录）")
@RestController
@RequestMapping("/app/content")
@RequiredArgsConstructor
public class AppContentController {

    private final ContentService contentService;

    @Operation(summary = "随机一条每日小贴士")
    @GetMapping("/tip/random")
    public Result<TipVO> randomTip() {
        return Result.success(contentService.randomTip());
    }

    @Operation(summary = "常见问题列表", description = "faqType：1回收端 2采购端；通用问题两端都会返回")
    @GetMapping("/faq")
    public Result<List<FaqVO>> faqList(@RequestParam(value = "type", required = false) Integer type) {
        return Result.success(contentService.listFaq(type));
    }

    @Operation(summary = "当前生效的公告", description = "按生效时间窗过滤；无公告时返回 null")
    @GetMapping("/announcement")
    public Result<AnnouncementVO> announcement() {
        return Result.success(contentService.activeAnnouncement());
    }

    @Operation(summary = "站点配置", description = "返回全部 KV 配置，前端启动时取一次即可")
    @GetMapping("/setting")
    public Result<Map<String, String>> settings() {
        return Result.success(contentService.allSettings());
    }
}
