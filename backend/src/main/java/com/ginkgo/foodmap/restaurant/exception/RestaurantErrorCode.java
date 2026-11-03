package com.ginkgo.foodmap.restaurant.exception;

import com.ginkgo.foodmap.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 餐厅读写的稳定错误码；公开读取将已删除与不存在统一为404，私有操作另校验归属与状态。 */
@Getter
@RequiredArgsConstructor
public enum RestaurantErrorCode implements ErrorCode {
    INVALID_ID("RESTAURANT_INVALID_ID", HttpStatus.BAD_REQUEST, "餐厅ID格式错误"),
    NOT_FOUND("RESTAURANT_NOT_FOUND", HttpStatus.NOT_FOUND, "餐厅不存在或不可访问"),
    REQUEST_NOT_FOUND("RESTAURANT_REQUEST_NOT_FOUND", HttpStatus.NOT_FOUND, "商户申请不存在"),
    PHOTO_NOT_FOUND("RESTAURANT_PHOTO_NOT_FOUND", HttpStatus.NOT_FOUND, "商户相册图片不存在或不可访问"),
    INVALID_INPUT("RESTAURANT_INVALID_INPUT", HttpStatus.BAD_REQUEST, "餐厅信息不符合保存规则"),
    CATEGORY_UNAVAILABLE("RESTAURANT_CATEGORY_UNAVAILABLE", HttpStatus.BAD_REQUEST, "分类不存在或已停用"),
    FORBIDDEN("RESTAURANT_FORBIDDEN", HttpStatus.FORBIDDEN, "仅管理员可维护正式餐厅资料"),
    STATE_CONFLICT("RESTAURANT_STATE_CONFLICT", HttpStatus.CONFLICT, "餐厅当前状态不支持此操作");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
