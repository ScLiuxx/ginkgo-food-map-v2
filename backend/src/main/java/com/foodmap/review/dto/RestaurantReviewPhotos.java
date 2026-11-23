package com.foodmap.review.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 公开给餐厅管理协作的同店点评图片，地址只指向受控点评读取入口。 */
public final class RestaurantReviewPhotos {
    private RestaurantReviewPhotos() {}

    @Data
    public static class Item {
        private String reviewId;
        private int position;
        private String url;
        private String username;
        private String body;
        private Instant createdAt;
    }

    @Data
    public static class Page {
        private List<Item> records;
        private boolean hasMore;
    }
}
