package com.nqx.service.file;

import cn.hutool.core.util.IdUtil;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 文件存储服务（统一入口）。
 *
 * <h2>两种存储后端，由 {@code nqx.upload.type} 切换</h2>
 *
 * <table border="1">
 *   <tr><th>type</th><th>存储位置</th><th>返回的 URL</th><th>适用场景</th></tr>
 *   <tr>
 *     <td>{@code local}</td><td>本地磁盘 {@code ./uploads}</td>
 *     <td>相对路径 {@code /files/...}</td>
 *     <td>本地开发</td>
 *   </tr>
 *   <tr>
 *     <td>{@code cos}</td><td>腾讯云 COS</td>
 *     <td>绝对地址 {@code https://bucket.cos.region.myqcloud.com/...}</td>
 *     <td>容器部署（云托管 / Docker / K8s）</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么必须可切换</b>：容器环境的磁盘是临时的（重建/扩容/滚动更新都会清空），
 * 本地模式在上面等于"图片存一次丢一次"，而且不报错。但本地开发时又不能强制依赖云存储。
 * 所以做成配置项，开发用 local、部署用 cos。
 *
 * <h2>文件上传是最容易被忽视的安全入口</h2>
 *
 * <p>很多项目把上传当成"存一下就行"，实际上它是 getshell 的经典途径。
 * 本实现针对性地做了四件事：
 *
 * <ol>
 *   <li><b>绝不使用用户提供的文件名。</b>
 *       若直接把 {@code file.getOriginalFilename()} 拼进路径，
 *       攻击者上传一个名为 {@code ../../../application.yml} 的文件，
 *       就能覆盖服务器上的任意文件（路径穿越漏洞）。
 *       这里一律用 UUID 重新命名，原文件名只作为日志记录。</li>
 *   <li><b>扩展名白名单，且大小写归一化。</b>
 *       黑名单（禁止 jsp、php…）永远不完备；而且 {@code .JSP}、{@code .jspx}
 *       等变体很容易绕过。这里只允许明确列出的图片扩展名。</li>
 *   <li><b>不信任 Content-Type。</b>
 *       {@code Content-Type} 由客户端提供，可以随意伪造，只能作为辅助信息，
 *       不能作为校验依据。真正的校验依据是扩展名白名单。</li>
 *   <li><b>存储 key 按日期分片（yyyy/MM/dd）。</b>
 *       无论是本地目录还是 COS，单前缀下堆积几十万个对象都会拖慢列举与运维操作。</li>
 * </ol>
 *
 * <p>这四条校验在<b>分流之前</b>统一完成 —— 换存储后端不改变安全边界，
 * 这是把校验放在这一层而不是各自实现里的理由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 仅用于日志与原文件名截断，不作为安全校验依据 */
    private static final int MAX_ORIGINAL_NAME_LOG_LENGTH = 100;

    /** COS 存储（type=cos 时才会真正启用；type=local 时它是个空壳） */
    private final CosFileStorage cosFileStorage;

    @Value("${nqx.upload.local-path:./uploads}")
    private String localPath;

    @Value("${nqx.upload.url-prefix:/files}")
    private String urlPrefix;

    /**
     * 允许的扩展名白名单。
     *
     * <p>用 {@link LinkedHashSet} 而非 List：校验时是 O(1) 查找，
     * 且报错信息里能按配置顺序稳定输出，方便排查。
     */
    @Value("${nqx.upload.allowed-extensions:jpg,jpeg,png,gif,webp,bmp}")
    private String allowedExtensionsConfig;

    /**
     * 保存上传文件。
     *
     * @param file    上传的文件
     * @param bizType 业务类型，用于二级目录归类（如 product、avatar、review、repair）
     * @return 可直接访问的 URL：local 模式是相对路径 {@code /files/...}，
     *         cos 模式是绝对地址 {@code https://...}
     */
    public String upload(MultipartFile file, String bizType) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.FILE_EMPTY);
        }

        String originalFilename = file.getOriginalFilename();
        String extension = resolveAndValidateExtension(originalFilename);

        // 校验并归一化业务目录，防止 bizType 里夹带 ../ 造成路径穿越
        String safeBizType = normalizeBizType(bizType);

        String dateDir = LocalDate.now().format(DATE_DIR_FORMATTER);
        String newFileName = IdUtil.fastSimpleUUID() + "." + extension;
        String relativeKey = safeBizType + "/" + dateDir + "/" + newFileName;

        // ---- 分流：COS 还是本地磁盘 ----
        if (cosFileStorage.isEnabled()) {
            String url = cosFileStorage.upload(file, relativeKey);
            log.info("文件上传成功（COS） | 原始文件名={} | key={} | 大小={}字节",
                    truncateForLog(originalFilename), relativeKey, file.getSize());
            return url;
        }

        return saveToLocal(file, relativeKey, originalFilename);
    }

    /** 本地磁盘存储（开发环境） */
    private String saveToLocal(MultipartFile file, String relativeKey, String originalFilename) {
        try {
            Path baseDir = Paths.get(localPath).toAbsolutePath().normalize();
            Path targetFile = baseDir.resolve(relativeKey).normalize();

            // 二次防线：解析后的路径必须仍在基目录之内。
            // 即便前面的扩展名/业务目录校验都被绕过，这一条也能兜住路径穿越。
            if (!targetFile.startsWith(baseDir)) {
                log.error("检测到疑似路径穿越的上传请求 | 目标路径={}", targetFile);
                throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
            }

            Files.createDirectories(targetFile.getParent());
            file.transferTo(targetFile.toFile());

            String url = urlPrefix + "/" + relativeKey;
            log.info("文件上传成功（本地） | 原始文件名={} | 存储路径={} | 大小={}字节",
                    truncateForLog(originalFilename), url, file.getSize());
            return url;

        } catch (IOException e) {
            log.error("文件写入失败 | 原始文件名={}", truncateForLog(originalFilename), e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 保存一段字节内容（用于程序生成的文件，目前只用于导出的 Excel）。
     *
     * <h3>为什么单独一个方法，而不是套用 {@link #upload(MultipartFile, String)}</h3>
     *
     * <p>报表是内存里生成的，没有 {@code MultipartFile}；而且它的扩展名由程序决定
     * （固定 {@code xlsx}），不需要也不应该走"图片扩展名白名单"那一套校验 ——
     * 那个白名单是给**用户上传**用的安全边界。
     *
     * <p>换句话说：<b>用户上传要严格校验，程序生成的可信内容不该被同一套规则卡住。</b>
     *
     * @param content  文件内容
     * @param bizType  业务目录名，如 {@code export}
     * @param nameHint 文件名提示（如「订单列表」）。
     *                 会被带进对象 key，这样用户下载时看到的是
     *                 {@code 订单列表_20261020153012.xlsx} 而不是一串 UUID ——
     *                 同一个链接下载两次也不会互相覆盖。
     * @return 可下载的 URL（local 模式是 {@code /files/export/...}，cos 模式是绝对地址）
     */
    public String storeXlsx(byte[] content, String bizType, String nameHint) {
        if (content == null || content.length == 0) {
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED, "生成的文件内容为空");
        }

        String safeBizType = normalizeBizType(bizType);
        String dateDir = LocalDate.now().format(DATE_DIR_FORMATTER);
        // 扩展名固定 xlsx，不接受调用方传入 —— 少一个可被构造的参数
        String fileName = sanitizeFileName(nameHint)
                + "_" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now())
                + ".xlsx";
        String relativeKey = safeBizType + "/" + dateDir + "/" + fileName;

        if (cosFileStorage.isEnabled()) {
            return cosFileStorage.upload(content, relativeKey,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        try {
            Path baseDir = Paths.get(localPath).toAbsolutePath().normalize();
            Path targetFile = baseDir.resolve(relativeKey).normalize();
            if (!targetFile.startsWith(baseDir)) {
                throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
            }
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, content);

            String url = urlPrefix + "/" + relativeKey;
            log.info("生成文件已保存（本地） | 路径={} | 大小={}字节", url, content.length);
            return url;
        } catch (IOException e) {
            log.error("生成文件写入失败 | key={}", relativeKey, e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 清洗文件名提示。
     *
     * <p>保留中文、字母、数字、下划线和短横线，其余替换成下划线。
     * 这样 {@code nameHint} 里即便混进 {@code ../} 或 {@code /} 也构不成路径穿越
     * （和 {@link #normalizeBizType} 是同一个思路：白名单过滤而非黑名单）。
     */
    private String sanitizeFileName(String nameHint) {
        if (!StringUtils.hasText(nameHint)) {
            return "export";
        }
        String cleaned = nameHint.trim().replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_-]", "_");
        // 限制长度，避免超长 key
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    /**
     * 解析并校验扩展名。
     *
     * <p>用 {@link StringUtils#getFilenameExtension(String)} 而不是自己按最后一个点切分：
     * 它能正确处理 {@code a.b.jpg}、无扩展名、以点结尾等边界情况。
     */
    private String resolveAndValidateExtension(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(extension)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED, "文件缺少扩展名");
        }
        // 统一转小写再比较，避免 .JPG 被放行/被拒绝造成的不一致
        String normalized = extension.toLowerCase(Locale.ROOT);
        if (!allowedExtensionSet().contains(normalized)) {
            throw new BizException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "仅支持以下格式：" + String.join("、", allowedExtensionSet()));
        }
        return normalized;
    }

    private Set<String> allowedExtensionSet() {
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(allowedExtensionsConfig.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(set::add);
        return set;
    }

    /**
     * 归一化业务目录名。
     *
     * <p>只允许字母、数字、下划线和短横线，其余一律替换掉。
     * 这样一个白名单式的字符过滤，能从根上杜绝 {@code bizType} 被构造成
     * {@code ../../} 这种路径穿越串。
     */
    private String normalizeBizType(String bizType) {
        if (!StringUtils.hasText(bizType)) {
            return "common";
        }
        String normalized = bizType.replaceAll("[^a-zA-Z0-9_-]", "");
        return normalized.isEmpty() ? "common" : normalized;
    }

    private String truncateForLog(String name) {
        if (name == null) {
            return null;
        }
        return name.length() <= MAX_ORIGINAL_NAME_LOG_LENGTH
                ? name : name.substring(0, MAX_ORIGINAL_NAME_LOG_LENGTH) + "...";
    }
}
