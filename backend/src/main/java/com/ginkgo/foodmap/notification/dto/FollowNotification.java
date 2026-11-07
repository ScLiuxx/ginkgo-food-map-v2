package com.ginkgo.foodmap.notification.dto;

import lombok.Data;

/** 经community确认的首次关注事实；来源为永久关系ID。 */
@Data
public class FollowNotification {
    private long recipientUserId;
    private long actorUserId;
    private long relationId;
}
