package com.ginkgo.foodmap.review.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

/** 点赞错误独立区分输入、归属和暂时竞争，源不存在与私有越权统一404。 */
@Getter
@RequiredArgsConstructor
public enum ContentLikeErrorCode implements ErrorCode {
    INVALID("LIKE_INVALID_INPUT", HttpStatus.BAD_REQUEST, "点赞参数无效"),
    SELF("LIKE_SELF_NOT_ALLOWED", HttpStatus.FORBIDDEN, "不能给自己的内容点赞"),
    MISSING("LIKE_TARGET_NOT_FOUND", HttpStatus.NOT_FOUND, "内容已失效或不可访问"),
    BUSY("LIKE_TEMPORARILY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "点赞暂时繁忙，请重试");
    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
