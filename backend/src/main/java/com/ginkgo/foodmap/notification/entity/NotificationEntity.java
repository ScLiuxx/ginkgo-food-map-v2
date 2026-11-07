package com.ginkgo.foodmap.notification.entity;

import java.time.Instant;
import lombok.Data;

/** notification自有持久化对象；摘要只存审核时快照，不复制申请全文或图片。 */
@Data
public class NotificationEntity {
    private Long id;
    private Long recipientUserId;
    private String notificationType = "RESTAURANT_DECISION";
    private Long sourceId;
    private Long reviewId;
    private Long actorUserId;
    private Long requestId;
    private String requestType;
    private String decision;
    private String restaurantName;
    private String rejectReason;
    private Instant createdAt;
    private Instant readAt;
}
