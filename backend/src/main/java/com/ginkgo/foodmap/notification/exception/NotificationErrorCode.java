package com.ginkgo.foodmap.notification.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 不存在与归属不符使用同一消息错误，不泄露其他人的收件箱记录。 */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    NOT_FOUND("NOTIFICATION_NOT_FOUND",HttpStatus.NOT_FOUND,"消息不存在或不可访问");
    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
