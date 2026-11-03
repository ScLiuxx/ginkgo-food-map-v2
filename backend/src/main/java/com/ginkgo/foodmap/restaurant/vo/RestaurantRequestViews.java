package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 管理员申请查看的白名单响应；不暴露请求摘要、邮箱、OSS对象键或文件持久化实体。 */
public final class RestaurantRequestViews {
    private RestaurantRequestViews() { }

    @Data
    public static class Item {
        private String id;
        private String requestType;
        private String status;
        private String name;
        private String restaurantId;
        private String submittedBy;
        private String submitterName;
        private Instant createdAt;
        private int photoCount;
    }

    @Data
    public static class Page {
        private List<Item> records;
        private long total;
        private long totalPages;
        private int page;
        private int pageSize;
        private Map<String, Long> counts;
    }

    @Data
    public static class Detail {
        private Item application;
        private Map<String, Object> proposed;
        private Map<String, Object> current;
        private boolean targetDeleted;
        private List<Photo> photos;
        /** 原投稿照片继续私有读取；此处另列实际发布的正式相册关联，避免将BOUND误认为公开。 */
        private List<RestaurantPhotoViews.Item> publishedPhotos;
        private String reviewedBy;
        private Instant reviewedAt;
        private String rejectReason;
        private Map<String, Object> approved;
        private Map<String, Object> before;
    }

    @Data
    public static class Photo {
        private int position;
        private String url;
    }

    /** SQL分组计数投影，供服务组装明确包含三个状态的计数。 */
    @Data
    public static class StatusCount {
        private String status;
        private long total;
    }
}
