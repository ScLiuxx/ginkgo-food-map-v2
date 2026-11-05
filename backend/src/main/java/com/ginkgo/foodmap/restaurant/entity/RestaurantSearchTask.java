package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;

/** 待处理任务只读取身份、来源版本及失败次数，商户正文另从已提交投影读取。 */
@Data
public class RestaurantSearchTask {
    private long id;
    private long restaurantId;
    private long projectionVersion;
    private int attempts;
}
