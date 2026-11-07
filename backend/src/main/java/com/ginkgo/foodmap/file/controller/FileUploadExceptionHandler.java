package com.ginkgo.foodmap.file.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** 容器解析multipart阶段的错误也使用统一响应，避免超限或缺字段被误报500。 */
@Order(-1)
@RestControllerAdvice(assignableTypes = ImageUploadController.class)
public class FileUploadExceptionHandler {
    /** 应用层与容器层共享300KiB上限，超限使用明确413状态。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge() {
        return ResponseEntity.status(413)
                .body(ApiResponse.error("FILE_TOO_LARGE", "单张图片不能超过300KiB"));
    }

    /** 请求必须提供名为file的文件字段。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> missingFile() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("FILE_INVALID_IMAGE", "请提供file图片字段"));
    }
}
