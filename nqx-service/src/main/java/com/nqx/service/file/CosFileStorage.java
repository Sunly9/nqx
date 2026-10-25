package com.nqx.service.file;

import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 腾讯云 COS 对象存储。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>本项目原来是「本地磁盘存储」（{@code ./uploads}），在**本地开发**完全没问题。
 * 但一旦部署到容器环境（微信云托管 / Docker / K8s），磁盘是临时：
 *
 * <ul>
 *   <li>容器重建、扩容、滚动更新 → 本地文件全部消失；</li>
 *   <li>多实例时每个实例有自己的磁盘，A 传的图 B 读不到；</li>
 *   <li>没有任何报错 —— 接口返回 200，数据库里的 URL 也在，只是图片打不开。</li>
 * </ul>
 *
 * <p>所以生产环境必须把文件放到对象存储。这里用腾讯云 COS，
 * 因为本项目已部署在微信云托管（腾讯云）体系内，同云厂商内网/计费都更顺。
 *
 * <h2>为什么配置缺失要「启动即失败」</h2>
 *
 * <p>如果 {@code type=cos} 但密钥/桶名没配齐，有两种处理方式：
 * <ol>
 *   <li>启动时放行，等用户上传时才报错 —— 结果是「系统看起来正常，
 *       但用户一传头像就失败」，问题被推迟到最不该出现的时刻；</li>
 *   <li><b>启动直接失败</b> —— 部署日志里立刻能看到原因。</li>
 * </ol>
 *
 * <p>本项目选第 2 种。这与 {@code WeChatApiClient} 在「未配凭证时拒绝登录」
 * 是同一个原则：<b>配置错误应当在最早的时刻暴露，而不是推迟到业务操作时。</b>
 *
 * <h2>返回的是绝对 URL</h2>
 *
 * <p>与本地存储返回相对路径（{@code /files/...}）不同，COS 返回的是
 * {@code https://bucket.cos.region.myqcloud.com/...} 这样的**绝对地址**。
 *
 * <p>原因是 COS 不在本站域名下，相对路径没有意义；而且小程序端
 * {@code rewriteAssetUrls} 只会给 {@code /files/} 开头的值补前缀，
 * 绝对地址会原样通过 —— 两种形态都能正常显示。
 */
@Slf4j
@Component
public class CosFileStorage {

    /** 存储类型开关：只有等于 {@code cos} 时本类才会被真正调用 */
    @Value("${nqx.upload.type:local}")
    private String storageType;

    @Value("${nqx.upload.cos.secret-id:}")
    private String secretId;

    @Value("${nqx.upload.cos.secret-key:}")
    private String secretKey;

    @Value("${nqx.upload.cos.region:ap-shanghai}")
    private String region;

    /** 桶名，形如 {@code mybucket-1300000000}（必须带 APPID 后缀） */
    @Value("${nqx.upload.cos.bucket:}")
    private String bucket;

    /**
     * key 前缀（相当于桶内的"根目录"）。
     *
     * <p>默认 {@code uploads}，目的是把程序上传的文件和桶里原有的手工素材分开，
     * 便于按前缀做生命周期策略、也便于误删时区分范围。
     */
    @Value("${nqx.upload.cos.key-prefix:uploads}")
    private String keyPrefix;

    /**
     * 对外访问域名（可选）。
     *
     * <p>不填时用默认域名 {@code https://<bucket>.cos.<region>.myqcloud.com}。
     * 如果桶绑定了 CDN 或自定义域名（例如 {@code https://img.example.com}），
     * 填在这里可以让返回的 URL 走 CDN，访问更快、也更便于以后换桶。
     */
    @Value("${nqx.upload.cos.domain:}")
    private String domain;

    private COSClient cosClient;

    /** COS 是否已被启用（type=cos 且配置齐全） */
    private boolean enabled;

    /**
     * 启动时校验配置并初始化客户端。
     *
     * <p>注意这里的判断顺序：先看 {@code type}，不是 cos 就直接跳过 ——
     * 本地开发（type=local）不该因为没配 COS 而启动失败。
     */
    @PostConstruct
    public void init() {
        if (!isCosType()) {
            log.info("文件存储：本地磁盘模式（nqx.upload.type={}）", storageType);
            enabled = false;
            return;
        }

        // 用一条消息把所有缺失项一次性列出来，避免"改一个、重启、再发现少一个"的反复
        StringBuilder missing = new StringBuilder();
        if (!StringUtils.hasText(secretId)) {
            missing.append(" COS_SECRET_ID");
        }
        if (!StringUtils.hasText(secretKey)) {
            missing.append(" COS_SECRET_KEY");
        }
        if (!StringUtils.hasText(bucket)) {
            missing.append(" COS_BUCKET");
        }
        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "nqx.upload.type=cos 但缺少必填配置：" + missing
                            + "。请补齐环境变量，或把 nqx.upload.type 改回 local。");
        }

        COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // COS 默认走 https；显式声明避免被 SDK 默认值变化影响
        clientConfig.setHttpProtocol(HttpProtocol.https);
        // 连接超时/读写超时可以留默认，但上传大图时建议放宽一点
        clientConfig.setConnectionTimeout(10_000);
        clientConfig.setSocketTimeout(60_000);

        cosClient = new COSClient(credentials, clientConfig);
        enabled = true;
        log.info("文件存储：腾讯云 COS 模式 | bucket={} | region={} | keyPrefix={}",
                bucket, region, keyPrefix);
    }

    @PreDestroy
    public void destroy() {
        if (cosClient != null) {
            // 必须关闭：COSClient 内部持有连接池与后台线程，不关会造成线程泄漏
            cosClient.shutdown();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 上传到 COS。
     *
     * @param file        文件
     * @param relativeKey 桶内相对 key，如 {@code product/2026/10/20/xxx.jpg}
     * @return 可直接访问的**绝对** URL
     */
    public String upload(MultipartFile file, String relativeKey) {
        if (!enabled) {
            // 理论上不会走到这里（FileStorageService 已按 type 分流），
            // 但保留兜底：宁可报错，也不要"以为传上去了其实是本地"
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED, "对象存储未启用");
        }

        try {
            return put(file.getInputStream(), file.getSize(),
                    file.getContentType(), relativeKey);
        } catch (IOException e) {
            log.error("读取上传文件失败 | key={}", relativeKey, e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 上传一段字节内容（用于程序生成的文件，比如导出的 Excel）。
     *
     * <p>为什么不复用 {@link #upload(MultipartFile, String)}：报表是内存里生成的，
     * 没有 {@code MultipartFile}。硬套一个假的 MultipartFile 是自找麻烦。
     */
    public String upload(byte[] content, String relativeKey, String contentType) {
        if (!enabled) {
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED, "对象存储未启用");
        }
        try {
            return put(new java.io.ByteArrayInputStream(content),
                    content.length, contentType, relativeKey);
        } catch (Exception e) {
            log.error("上传字节内容至 COS 失败 | key={} | size={}", relativeKey, content.length, e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /** 真正的 PUT 逻辑，两条入口共用 */
    private String put(java.io.InputStream in, long size, String contentType, String relativeKey) {
        String key = StringUtils.hasText(keyPrefix)
                ? trimSlashes(keyPrefix) + "/" + relativeKey
                : relativeKey;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        // 显式设置 Content-Type：不设的话 COS 会用默认值，
        // 浏览器打开图片链接可能变成"下载"而不是直接显示
        if (StringUtils.hasText(contentType)) {
            metadata.setContentType(contentType);
        }

        try {
            cosClient.putObject(bucket, key, in, metadata);
        } catch (Exception e) {
            // COS SDK 抛的异常类型很杂（ClientException / CosServiceException…），
            // 统一收口成业务异常，避免把 SDK 细节泄露给前端
            log.error("上传至 COS 失败 | key={} | bucket={}", key, bucket, e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
        }

        String url = buildUrl(key);
        log.info("文件已上传至 COS | key={} | 大小={}字节 | url={}", key, size, url);
        return url;
    }

    /**
     * 拼接访问 URL。
     *
     * <p>优先用 {@code domain}（CDN/自定义域名），否则用默认域名。
     * 注意 bucket 名本身已包含 APPID（如 {@code xxx-1300000000}），
     * 这就是 COS 默认域名的第一段，不要再拼一次 APPID。
     */
    private String buildUrl(String key) {
        if (StringUtils.hasText(domain)) {
            return trimTrailingSlash(domain) + "/" + key;
        }
        return "https://" + bucket + ".cos." + region + ".myqcloud.com/" + key;
    }

    private boolean isCosType() {
        return "cos".equalsIgnoreCase(StringUtils.trimWhitespace(storageType));
    }

    private String trimSlashes(String s) {
        String r = s.trim();
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private String trimTrailingSlash(String s) {
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
