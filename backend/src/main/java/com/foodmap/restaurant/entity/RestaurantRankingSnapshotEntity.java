package com.foodmap.restaurant.entity;

import lombok.Data;

import java.time.Instant;

/** MySQL快照的内部映射；JSON及规则只在模块内解析，不直接作为HTTP响应暴露。 */
@Data
public class RestaurantRankingSnapshotEntity {
    private String campusCode;
    private String periodType;
    private Instant windowStartAt;
    private Instant windowEndAt;
    private Instant generatedAt;
    private String rulesSnapshot;
    private String items;
}
