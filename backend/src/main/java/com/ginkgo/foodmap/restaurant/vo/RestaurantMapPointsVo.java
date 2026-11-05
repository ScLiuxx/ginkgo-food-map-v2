package com.ginkgo.foodmap.restaurant.vo;

import java.util.List;
import lombok.Data;

/** 同一只读快照内的视口计数及受控点位，不将截断集合称为全部。 */
@Data
public class RestaurantMapPointsVo {
    private RestaurantSearchStatus search;
    private List<RestaurantMapPointVo> points;
    private long matchedCount;
    private int returnedCount;
    private int limit;
    private boolean truncated;
}
