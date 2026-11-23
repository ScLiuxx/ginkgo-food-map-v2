package com.foodmap.statistics.vo;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 管理端只读投影，不包含账号成员。 */
@Data
public class ActivityStatisticsVo {
    private boolean enabled;
    private String timezone = "Asia/Shanghai";
    private Instant startedAt;
    private LocalDate currentDate;
    private Instant fetchedAt;
    private Long sevenDayActiveUserCount;
    private String sevenDayCollectionStatus = "UNKNOWN";
    private List<Day> days = List.of();

    @Data
    public static class Day {
        private LocalDate date;
        private Long activeUserCount;
        private Long newUserCount;
        private Long publishedReviewCount;
        private String collectionStatus;
        private Instant activeSyncedAt;
    }
}
