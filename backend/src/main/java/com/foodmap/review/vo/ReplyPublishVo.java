package com.foodmap.review.vo;

import lombok.Data;

/** 发布回执不携带持久化摘要或认证数据。 */
@Data
public class ReplyPublishVo {
    private String replyId;
    private String reviewId;
    private boolean replayed;
}
