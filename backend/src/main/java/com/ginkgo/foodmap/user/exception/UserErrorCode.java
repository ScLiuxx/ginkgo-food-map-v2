package com.ginkgo.foodmap.user.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 用户注册和账号管理模块的稳定业务错误码。
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    PUBLIC_PROFILE_NOT_FOUND("USER_PUBLIC_PROFILE_NOT_FOUND", HttpStatus.NOT_FOUND, "用户主页不可用"),
    PUBLIC_PROFILE_INVALID("USER_PUBLIC_PROFILE_INVALID", HttpStatus.BAD_REQUEST, "用户编号无效"),
    BIO_INVALID("USER_BIO_INVALID", HttpStatus.BAD_REQUEST, "个人简介包含不支持的字符"),
    BIO_TOO_LONG("USER_BIO_TOO_LONG", HttpStatus.BAD_REQUEST, "个人简介最多100字"),
    BIO_CONTACT_NOT_ALLOWED("USER_BIO_CONTACT_NOT_ALLOWED", HttpStatus.BAD_REQUEST, "个人简介不能包含微信、QQ、手机号或网址"),
    PROFILE_UNAVAILABLE("USER_PROFILE_UNAVAILABLE", HttpStatus.UNAUTHORIZED, "账号不可用，请重新登录"),
    CAMPUS_EMAIL_REQUIRED("USER_CAMPUS_EMAIL_REQUIRED", HttpStatus.BAD_REQUEST, "请使用允许的校园邮箱"),
    EMAIL_ALREADY_REGISTERED("USER_EMAIL_ALREADY_REGISTERED", HttpStatus.CONFLICT, "该邮箱已注册"),
    USERNAME_ALREADY_REGISTERED("USER_USERNAME_ALREADY_REGISTERED", HttpStatus.CONFLICT, "该用户名已被使用"),
    USERNAME_INVALID("USER_USERNAME_INVALID", HttpStatus.BAD_REQUEST, "用户名只能包含文字、数字和下划线，且长度必须符合要求"),
    PASSWORD_WEAK("USER_PASSWORD_WEAK", HttpStatus.BAD_REQUEST, "密码长度或强度不符合要求"),
    REGISTRATION_CONFLICT("USER_REGISTRATION_CONFLICT", HttpStatus.CONFLICT, "邮箱或用户名已被使用，请更换后重试"),
    PASSWORD_RESET_CONFLICT("USER_PASSWORD_RESET_CONFLICT", HttpStatus.CONFLICT, "账号状态已变化，请重新获取验证码后重试"),
    PASSWORD_RESET_UNAVAILABLE("USER_PASSWORD_RESET_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "密码重置服务暂时不可用，请重新获取验证码后重试");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
