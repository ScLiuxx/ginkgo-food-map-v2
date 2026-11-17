package com.foodmap.review.entity;

import lombok.Data;

/** reviews计数投影；与HTTP分页对象分离，避免持久化映射直接依赖响应类型。 */
@Data
public class ReviewCountsEntity {
    private long totalGroups;
    private long totalReviews;
}
