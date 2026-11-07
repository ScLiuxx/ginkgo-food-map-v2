package com.ginkgo.foodmap.notification.dto;

import lombok.Data;

/** 回复模块在持有父店/点评锁时确定的事实；不接受客户端收件人，也不复制回复正文。 */
@Data
public class ReviewReplyNotification {
    private long recipientUserId;
    private long actorUserId;
    private long reviewId;
    private long replyId;
}
