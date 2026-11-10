package com.ginkgo.foodmap.moderation.entity;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

/** moderation私有持久化对象；正文快照与文字说明不进入日志输出。 */
@Data
public class ContentReportEntity {
    private Long id;
    private long reporterUserId;
    private String targetType;
    private long targetId;
    private long targetAuthorUserId;
    private long reviewId;
    @ToString.Exclude private String contentSnapshot;
    private String reason;
    @ToString.Exclude private String detail;
    private String status;
    private Long handledBy;
    private Instant handledAt;
    private String decisionNote;
    private Instant createdAt;
}
