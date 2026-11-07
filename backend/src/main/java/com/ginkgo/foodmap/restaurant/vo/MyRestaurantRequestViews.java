package com.ginkgo.foodmap.restaurant.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** 本人收件视角的显式白名单，不能复用包含管理快照的管理员详情。 */
public final class MyRestaurantRequestViews {
    private MyRestaurantRequestViews() { }

    @Data
    public static class Item {
        private String id;
        private String restaurantId;
        private String requestType;
        private String status;
        private String name;
        private Instant createdAt;
        private Instant reviewedAt;
        private int photoCount;
    }

    @Data
    public static class Page {
        private String userId;
        private List<Item> records;
        private Map<String, Long> counts;
        private long total;
        private long totalPages;
        private int page;
        private int pageSize;
    }

    @Data
    public static class Detail {
        private String userId;
        private Item application;
        private Map<String, Object> proposed;
        private Map<String, Object> approved;
        private String rejectReason;
        private boolean targetAvailable;
        /** 仅返回申请内位置；图片地址由申请ID/位置生成，不泄露文件ID或存储键。 */
        private List<Integer> photoPositions;
    }

    @Data
    public static class StatusCount {
        private String status;
        private long total;
    }
}
