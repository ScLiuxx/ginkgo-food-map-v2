package com.ginkgo.foodmap.notification.dto;

import lombok.Data;

/** 仅由已锁定并核验内容的review服务提供首次点赞事实，不接受HTTP直接发送。 */
@Data
public class ContentLikeNotification {
    private long recipientUserId;
    private long actorUserId;
    private long likeId;
    private long reviewId;
    private String targetType;
}
