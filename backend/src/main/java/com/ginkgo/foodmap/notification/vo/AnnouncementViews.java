package com.ginkgo.foodmap.notification.vo;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 公告白名单响应，不暴露请求摘要、个人删除记录或内部UUID二进制。 */
public final class AnnouncementViews {
    private AnnouncementViews() {}

    @Data
    public static class Detail {
        private String id;
        private String title;
        private String content;
        private String status;
        private String publishedBy;
        private Instant publishedAt;
        private String withdrawnBy;
        private Instant withdrawnAt;
        private boolean replayed;
    }

    @Data
    public static class Page {
        private List<Detail> records;
        private long total;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
