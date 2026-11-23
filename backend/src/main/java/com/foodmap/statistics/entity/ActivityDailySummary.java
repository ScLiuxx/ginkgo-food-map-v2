package com.foodmap.statistics.entity;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/** statistics自有日汇总；NULL计数不代表0。 */
@Data
public class ActivityDailySummary {
    private LocalDate statDate;
    private Long activeUserCount;
    private Long newUserCount;
    private Long publishedReviewCount;
    private String collectionStatus;
    private Instant activeSyncedAt;
}
