package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** 窗口公开白名单；无文件私钥、原请求摘要或删除操作者。 */
public final class CanteenWindowViews {
    private CanteenWindowViews() {}

    @Data
    public static class Item {
        private String id;
        private String canteenId;
        private String canteenName;
        private String campusCode;
        private String name;
        private Integer floorNumber;

        private String description;
        private String coverUrl;
        private String priceKind;
        private Integer pricePerPerson;
        private String businessStatus;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean deleted;
        private boolean replayed;
        private BigDecimal score;
        private BigDecimal taste;
        private BigDecimal value;
        private BigDecimal service;
        private BigDecimal ambience;
        private BigDecimal recommendRate;
        private long reviewCount;
        private long participantCount;
        private String address;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private boolean needsLocation;
    }
}
