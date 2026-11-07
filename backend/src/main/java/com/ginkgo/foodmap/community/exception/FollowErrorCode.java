package com.ginkgo.foodmap.community.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;

import lombok.*;

import org.springframework.http.HttpStatus;

/** 关注边界的稳定错误码，不回显私密名单或其他用户记录。 */
@Getter
@RequiredArgsConstructor
public enum FollowErrorCode implements ErrorCode {
    INVALID("FOLLOW_INVALID_ID", HttpStatus.BAD_REQUEST, "用户编号无效"),
    SELF("FOLLOW_SELF_FORBIDDEN", HttpStatus.BAD_REQUEST, "不能关注自己"),
    PRIVATE("FOLLOW_LIST_PRIVATE", HttpStatus.FORBIDDEN, "该用户名单仅本人可见");
    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
