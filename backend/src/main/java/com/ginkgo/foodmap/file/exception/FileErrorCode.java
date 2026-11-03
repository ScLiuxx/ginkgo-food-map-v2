package com.ginkgo.foodmap.file.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 图片错误仅包含安全业务提示，不回显OSS异常、对象路径或凭证。 */
@Getter
@RequiredArgsConstructor
public enum FileErrorCode implements ErrorCode {
    INVALID_IMAGE("FILE_INVALID_IMAGE", HttpStatus.BAD_REQUEST, "请选择不超过300KiB、边长不超过1200px的有效JPEG图片"),
    NOT_FOUND("FILE_NOT_FOUND", HttpStatus.NOT_FOUND, "临时图片不存在或不能操作"),
    BOUND("FILE_ALREADY_BOUND", HttpStatus.CONFLICT, "图片已绑定，请先确认提交结果"),
    NOT_BINDABLE("FILE_NOT_BINDABLE", HttpStatus.CONFLICT, "图片已过期、尚未上传完成或不能绑定，请重新上传"),
    UNAVAILABLE("FILE_STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "图片存储暂不可用，请稍后重试");
    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
