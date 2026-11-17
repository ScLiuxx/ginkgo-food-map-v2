package com.foodmap.restaurant.support;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.exception.RestaurantErrorCode;

/** 模块内统一解析字符串ID，拒绝long溢出、符号和前导零，避免浏览器整数精度问题。 */
public final class RestaurantIdentifiers {
    private RestaurantIdentifiers() { }

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(RestaurantErrorCode.INVALID_ID);
        }
    }
}
