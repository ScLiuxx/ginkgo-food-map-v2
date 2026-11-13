package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

import java.util.List;

/** 同一只读快照中的公开候选与计数，不持久化用户位置或选店历史。 */
@Data
public class RestaurantPickerVo {
    private List<RestaurantVo> records;

    /** 当前满足全部硬条件的总数，包含本轮已经看过但目前仍符合条件的店。 */
    private int matchedCount;

    /** 当前未被excludeIds排除、且未在本次抽取中返回的数量；仅表示本次快照。 */
    private int remainingCount;
}
