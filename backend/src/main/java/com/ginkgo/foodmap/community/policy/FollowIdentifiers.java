package com.ginkgo.foodmap.community.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.community.exception.FollowErrorCode;

/** 关注模块字符串ID边界，拒绝前导零、负数及溢出。 */
public final class FollowIdentifiers {
    private FollowIdentifiers() {}

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(FollowErrorCode.INVALID);
        }
    }
}
