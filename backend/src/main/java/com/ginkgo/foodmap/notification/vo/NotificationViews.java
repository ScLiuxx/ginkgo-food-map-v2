package com.ginkgo.foodmap.notification.vo;

import java.time.Instant;
import java.util.List;
import lombok.Data;

/** 私有收件箱响应；所有ID使用字符串，避免浏览器丢失BIGINT精度。 */
public final class NotificationViews {
    private NotificationViews() { }
    @Data
    public static class Item {
        private String id;
        private String requestId;
        private String notificationType;
        private String category = "SYSTEM";
        private String sourceId;
        private String reviewId;
        private String actorUserId;
        private String actorUsername;
        private String requestType;
        private String decision;
        private String restaurantName;
        private String rejectReason;
        private Instant createdAt;
        private Instant readAt;
    }
    @Data
    public static class Page {
        private String userId;
        private String category;
        private List<Item> records;
        private long total;
        private long unreadCount;
        private java.util.Map<String,Long> unreadCounts;
        private Item latestSystem;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
