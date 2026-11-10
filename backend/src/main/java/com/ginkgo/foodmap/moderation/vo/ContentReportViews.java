package com.ginkgo.foodmap.moderation.vo;

import lombok.Data;

import java.time.Instant;

/** 举报提交回执；不返回内容快照，避免把管理员材料回显给普通用户。 */
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
}
