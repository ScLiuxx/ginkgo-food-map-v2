package com.ginkgo.foodmap.security.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 认证失败使用稳定且不暴露账号是否存在的错误码。 */
@Getter
@RequiredArgsConstructor
public enum AuthenticationErrorCode implements ErrorCode {
    REGISTER_LOGIN_FAILED("AUTH_REGISTER_LOGIN_FAILED", HttpStatus.SERVICE_UNAVAILABLE,
            "账号已创建，但自动登录失败，请前往登录"),
    INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "账号或密码错误，或账号暂不可用"),
    LOGIN_LOCKED("AUTH_LOGIN_LOCKED", HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试"),
    SERVICE_UNAVAILABLE("AUTH_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂时不可用，请稍后重试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
