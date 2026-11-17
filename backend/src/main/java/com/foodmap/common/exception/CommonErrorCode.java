package com.foodmap.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 与具体业务无关的公共错误码。
 *
 * <p>错误码一旦提供给前端就应保持稳定；中文提示可以后续国际化，但不能作为前端分支判断条件。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED("COMMON_VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    MALFORMED_REQUEST("COMMON_MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, "请求内容格式错误"),
    METHOD_NOT_ALLOWED("COMMON_METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    DATA_CONFLICT("COMMON_DATA_CONFLICT", HttpStatus.CONFLICT, "数据状态冲突"),
    INTERNAL_SERVER_ERROR("COMMON_INTERNAL_SERVER_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
