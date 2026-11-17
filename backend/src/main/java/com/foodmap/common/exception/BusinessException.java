package com.foodmap.common.exception;

import lombok.Getter;

import java.util.Objects;

/**
 * 业务规则未满足时抛出的可预期异常。
 *
 * <p>异常只携带可公开错误码和提示，不应保存密码、验证码、Token 等敏感数据。
 * 未预期的技术异常不得包装成该类型来隐藏故障，应交给全局兜底处理并记录服务端日志。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认安全提示创建业务异常。
     *
     * @param errorCode 业务模块或公共模块定义的错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").getDefaultMessage());
    }

    /**
     * 使用经过审查的自定义提示创建业务异常。
     *
     * @param errorCode 错误码及 HTTP 状态
     * @param message   可安全返回给调用者的提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(requireMessage(message));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
