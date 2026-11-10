package com.ginkgo.foodmap.moderation.vo;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** 举报提交回执与管理员处置视图；普通用户响应不包含正文快照。 */
public final class ContentReportViews {
    private ContentReportViews() {}

    @Data
    public static class Submitted {
        /** 所有编号使用字符串，避免浏览器丢失BIGINT精度。 */
        private String reportId;
        private String targetType;
        private String targetId;
        private String status;
        /** 同一用户重复提交时为true，本次没有新建记录。 */
        private boolean duplicate;
        private Instant createdAt;
    }

    /** 管理员举报明细；快照和举报人只在本视图出现，需由管理安全链保护。 */
    @Data
    public static class AdminItem {
        private String id;
        private String targetType;
        private String targetId;
        private String reviewId;
        private String reporterUserId;
        private String targetAuthorUserId;
        private String contentSnapshot;
        private String reason;
        private String detail;
        private String status;
        private String handledBy;
        private Instant handledAt;
        private String decisionNote;
        private Instant createdAt;
    }

    @Data
    public static class AdminPage {
        private List<AdminItem> records;
        private long total;
        private long totalPages;
        private int page;
        private int pageSize;
    }

    /** 驳回回执；affectedReports包含同一内容下被一并处理的其他举报。 */
    @Data
    public static class Dismissed {
        private String reportId;
        private String status;
        private int affectedReports;
        private Instant handledAt;
    }
}
