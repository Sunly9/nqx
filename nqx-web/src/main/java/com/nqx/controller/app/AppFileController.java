package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.service.file.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 小程序端文件接口。
 *
 * <h2>为什么需要这个接口</h2>
 *
 * <p>小程序修改头像时，{@code wx.chooseAvatar} 返回的是一个
 * <b>临时文件路径</b>（形如 {@code http://tmp/xxxx.jpeg} 或
 * {@code wxfile://tmp_xxx.jpeg}）。这个路径有两个问题：
 *
 * <ul>
 *   <li>只存在于当前微信客户端会话中，<b>重启小程序或换设备就失效</b>；</li>
 *   <li>它指向的是手机本地文件，服务端根本读不到。</li>
 * </ul>
 *
 * <p>原来的实现直接把临时路径写进了 {@code app_user.avatar_url} ——
 * 数据库里存的是一串明天就打不开的地址。表现就是：用户当天换了头像，
 * 第二天头像变回默认，而且**没有任何报错**。
 *
 * <p>所以正确做法是：先上传取得持久化的 {@code /files/...} 地址，再保存。
 *
 * <h2>为什么不用 {@code /admin/file/upload}</h2>
 *
 * <p>那是管理端接口，路径前缀 {@code /admin/**} 在 {@code SaTokenConfig} 里
 * 走的是**管理端登录类型**（{@code StpAdminLogic}）。小程序用户拿的是 app token，
 * 调它必然 401。两端各有一套登录体系，接口也必须各有一套入口。
 *
 * <h2>鉴权</h2>
 *
 * <p>本接口<b>要求登录</b>（{@code /app/**} 默认拦截，且不在放行名单里）。
 * 上传是安全敏感操作，不能匿名开放 —— 否则任何人都能往服务器写文件。
 */
@Tag(name = "小程序端-文件", description = "图片上传（头像、评价晒图、维修凭证等）")
@RestController
@RequestMapping("/app/file")
@RequiredArgsConstructor
public class AppFileController {

    private final FileStorageService fileStorageService;

    /**
     * 上传图片。
     *
     * @param file    图片文件
     * @param bizType 业务目录名（avatar / review / repair …），由服务端白名单过滤字符，
     *                只保留字母数字下划线短横线，从根上杜绝 {@code ../} 路径穿越
     * @return 可直接访问的**相对** URL，如 {@code /files/avatar/2026/10/19/xxx.jpg}
     *
     * <p>返回相对地址而不是带域名的绝对地址：写死域名后换台机器、换个端口、
     * 上云托管，数据库里的图片就全挂了。小程序端需要绝对地址时由
     * {@code utils/request.js} 的 {@code rewriteAssetUrls} 补全。
     */
    @Operation(summary = "图片上传",
            description = "仅允许图片扩展名；存储路径与文件名均由服务端生成，不使用客户端提供的文件名")
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "bizType", required = false, defaultValue = "common")
                                 String bizType) {
        return Result.success("上传成功", fileStorageService.upload(file, bizType));
    }
}
