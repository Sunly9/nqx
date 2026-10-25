package com.nqx.controller.admin;

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
 * 管理端文件接口。
 *
 * <p>权限说明：V2 初始化数据里没有为文件上传单独定义权限码，
 * 因此这里只要求登录（由 {@code SaTokenConfig} 的拦截器保证）。
 * 上传是安全敏感操作，若将来要做细粒度控制，
 * 应在 {@code sys_permission} 中补一条 {@code file:upload} 权限码并加注解。
 */
@Tag(name = "管理端-文件", description = "图片上传")
@RestController
@RequestMapping("/admin/file")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "图片上传",
            description = "仅允许图片扩展名；存储路径与文件名均由服务端生成，不使用客户端提供的文件名")
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "bizType", required = false, defaultValue = "common")
                                 String bizType) {
        return Result.success("上传成功", fileStorageService.upload(file, bizType));
    }
}
