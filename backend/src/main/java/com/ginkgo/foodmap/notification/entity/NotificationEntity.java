package com.ginkgo.foodmap.notification.entity;

import lombok.Data;

import java.time.Instant;

/** notification自有持久化对象；摘要只存审核时快照，不复制申请全文或图片。 */
@Data
public class NotificationEntity {
    /** 合并查询的公告标题投影；普通通知为NULL，不增加notification_messages列。 */
    private String announcementTitle;

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
