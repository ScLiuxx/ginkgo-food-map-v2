package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 收藏接口白名单，不包含收藏总人数、商户删除原因或账号隐私字段。 */
public final class FavoriteViews {
    private FavoriteViews() {}

    @Data
    public static class State {
        private String restaurantId;
        private boolean favorited;
    }

    @Data
    public static class Item {
        private String restaurantId;
        private String name;
        private String restaurantType;
        private String businessStatus;
        private boolean available;
        private Instant createdAt;
    }

    @Data
    public static class Page {
        private String userId;
        private List<Item> records = List.of();
        private long total;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
