package com.foodmap.restaurant.vo;

import lombok.Data;

import java.util.List;

/** 同一只读快照内的视口计数及受控点位，不将截断集合称为全部。 */
@Data
public class RestaurantMapPointsVo {
    private RestaurantSearchStatus search;
    private List<RestaurantMapPointVo> points;

    /** 与points逐项同序的公开卡片资料；地图和列表共用本次响应，不再另行分页查询。 */
    private List<RestaurantVo> records;

    private long matchedCount;
    private int returnedCount;
    private int limit;
    private boolean truncated;
}
