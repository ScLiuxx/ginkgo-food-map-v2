package com.ginkgo.foodmap.review.vo;

import lombok.Data;

/** 发布只返回资源身份及是否重放，不把旧请求的评分快照当作当前全店状态。 */
@Data
public class ReviewPublishVo {
    private String reviewId;
    private String restaurantId;
    private String windowId;
    private boolean replayed;
}
