package com.foodmap.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 可由各业务模块实现的统一错误码协议。
 *
 * <p>公共模块只约定错误码、HTTP 状态和默认提示的形状。用户、商户等模块可以定义自己的枚举，
 * 从而避免公共模块反向依赖具体业务规则。</p>
 */
public interface ErrorCode {

    /** @return 稳定且机器可读的错误码 */
    String getCode();

    /** @return 与错误语义匹配的 HTTP 状态 */
    HttpStatus getHttpStatus();

    /** @return 默认的非敏感用户提示 */
    String getDefaultMessage();
}
