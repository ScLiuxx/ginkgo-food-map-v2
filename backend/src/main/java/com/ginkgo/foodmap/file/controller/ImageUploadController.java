package com.ginkgo.foodmap.file.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.service.ImageUploadService;
import com.ginkgo.foodmap.file.vo.ImageUploadVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

/** 认证由安全链执行；不提供临时预览接口，前端继续展示本地压缩图片。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files/images")
public class ImageUploadController {
    private final ImageUploadService service;

    /** 单张multipart上传；统一响应避免返回Bucket或任意对象地址。 */
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageUploadVo>> upload(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue="REVIEW") String purpose) {
        try (var stream=file.getInputStream()) {
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.upload(stream,purpose)));
        } catch (IOException e) { throw new BusinessException(FileErrorCode.INVALID_IMAGE); }
    }

    /** 先提交取消意图再立即尝试OSS删除；已完成204，未确认202并保留后台重试。 */
    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String fileId) {
        boolean removed=service.cancel(fileId);
        return ResponseEntity.status(removed?204:202).cacheControl(CacheControl.noStore())
                .body(removed?null:ApiResponse.ok(null));
    }
}
