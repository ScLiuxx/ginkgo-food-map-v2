package com.ginkgo.foodmap.review.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;

/** 点评读取入口的资源ID校验，不携带评分分档或展示规则。 */
public final class ReviewReadPolicy {
    private ReviewReadPolicy() { }

    /** ID使用规范正整数且不超出Java long，非法输入不会进入SQL。 */
    public static long restaurantId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return Long.parseLong(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ReviewErrorCode.INVALID_QUERY);
        }
    }

}
