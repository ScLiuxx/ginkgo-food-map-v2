package com.ginkgo.foodmap.review.vo;

import java.time.Instant;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import lombok.Data;

/** 本人收到回复时精确定位原点评与目标回复，不通过逐页遍历或保存正文快照定位。 */
@Data
public class ReplyTargetVo {
    private String userId;
    private String restaurantId;
    private String reviewId;
    private String replyId;
    private PublicAuthor reviewAuthor;
    private ReviewPageVo.CurrentReview review;
    private PublicAuthor replyAuthor;
    private String replyBody;
    private Instant replyCreatedAt;
}
