package com.ginkgo.foodmap.review.vo;

import com.ginkgo.foodmap.user.dto.PublicAuthor;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 点赞响应只提供稳定目标身份、当前状态及计数，不返回其他点赞者名单。 */
public final class ContentLikeViews {
    private ContentLikeViews() {}

    @Data
    public static class State {
        private String targetId;
        private long count;
        private boolean liked;
        private boolean available;
    }

    @Data
    public static class Batch {
        private String userId;
        private String type;
        private List<State> records;
    }

    @Data
    public static class Target {
        private String userId;
        private String likeId;
        private String targetType;
        private String targetId;
        private String reviewId;
        private String restaurantId;
        private PublicAuthor author;
        private String body;
        private Instant createdAt;
    }

    @Data
    public static class Total {
        private String userId;
        private long count;
    }
}
