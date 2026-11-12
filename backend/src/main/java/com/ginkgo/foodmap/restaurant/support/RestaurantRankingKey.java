package com.ginkgo.foodmap.restaurant.support;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;

import java.util.List;
import java.util.Set;

/** 四个合法榜单的不可变标识；键空间固定，避免通配符清理或客户端任意构造缓存键。 */
public record RestaurantRankingKey(String campusCode, String periodType) {
    public static final List<RestaurantRankingKey> ALL = List.of(
            new RestaurantRankingKey("QINGSHUIHE", "WEEK"), new RestaurantRankingKey("QINGSHUIHE", "MONTH"),
            new RestaurantRankingKey("SHAHE", "WEEK"), new RestaurantRankingKey("SHAHE", "MONTH"));

    /** 应用服务与HTTP之外的内部调用也必须验证编码。 */
    public RestaurantRankingKey {
        if (campusCode == null || !Set.of("QINGSHUIHE", "SHAHE").contains(campusCode)
                || periodType == null || !Set.of("WEEK", "MONTH").contains(periodType)) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "请选择有效校区和榜单周期");
        }
    }

    public String suffix() {
        return campusCode + ":" + periodType;
    }

    public long windowHours() {
        return "WEEK".equals(periodType) ? 168 : 720;
    }
}
