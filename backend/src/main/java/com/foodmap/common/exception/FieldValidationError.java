package com.foodmap.common.exception;

import lombok.Data;

/**
 * 可安全返回给客户端的字段校验错误。
 *
 */
@Data
public final class FieldValidationError {

    private final String field;
    private final String message;
}
