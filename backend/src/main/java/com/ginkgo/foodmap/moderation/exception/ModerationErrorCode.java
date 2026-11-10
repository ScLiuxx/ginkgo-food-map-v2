package com.ginkgo.foodmap.moderation.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

/** 举报错误独立区分输入、目标失效和自举报，目标不存在与不可见统一返回404。 */
@Getter
@RequiredArgsConstructor
public enum ModerationErrorCode implements ErrorCode {
    INVALID_INPUT("REPORT_INVALID_INPUT", HttpStatus.BAD_REQUEST, "举报参数无效"),
    TARGET_NOT_FOUND("REPORT_TARGET_NOT_FOUND", HttpStatus.NOT_FOUND, "举报内容已失效或不可访问"),
    SELF("REPORT_SELF_NOT_ALLOWED", HttpStatus.FORBIDDEN, "不能举报自己发布的内容"),
    NOT_FOUND("REPORT_NOT_FOUND", HttpStatus.NOT_FOUND, "举报记录不存在"),
    STATE_CONFLICT("REPORT_STATE_CONFLICT", HttpStatus.CONFLICT, "举报已处理，不能驳回");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
