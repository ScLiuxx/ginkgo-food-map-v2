package com.ginkgo.foodmap.review.entity;

import lombok.Data;

import java.time.Instant;

/** review模块内部目标投影，只读取本模块点评与回复。 */
@Data
public class LikeTargetEntity {
    private long id;
    private long reviewId;
    private Long restaurantId;
    private long authorUserId;
    private String body;
    private Instant createdAt;
}
