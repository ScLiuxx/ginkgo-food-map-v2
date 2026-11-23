package com.foodmap.user.exception;

import com.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 邮箱验证码模块的稳定错误码。
 */
@Getter
@RequiredArgsConstructor
public enum VerificationCodeErrorCode implements ErrorCode {
    RESEND_COOLDOWN("AUTH_CODE_RESEND_COOLDOWN", HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试"),
    EMAIL_RATE_LIMITED("AUTH_CODE_EMAIL_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "该邮箱发送次数过多，请稍后再试"),
    INVALID_OR_EXPIRED("AUTH_CODE_INVALID_OR_EXPIRED", HttpStatus.BAD_REQUEST, "验证码错误或已失效"),
    ATTEMPTS_EXCEEDED("AUTH_CODE_ATTEMPTS_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取"),
    DELIVERY_STATE_EXPIRED("AUTH_CODE_DELIVERY_STATE_EXPIRED", HttpStatus.SERVICE_UNAVAILABLE, "验证码发送状态已失效，请重新获取"),
    MAIL_DELIVERY_FAILED("AUTH_CODE_MAIL_DELIVERY_FAILED", HttpStatus.SERVICE_UNAVAILABLE, "验证码邮件发送失败，请稍后再试"),
    SERVICE_UNAVAILABLE("AUTH_CODE_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂时不可用，请稍后再试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
