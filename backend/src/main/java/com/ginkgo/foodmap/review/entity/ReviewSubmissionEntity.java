package com.ginkgo.foodmap.review.entity;

import com.ginkgo.foodmap.review.dto.ReviewRecommendation;

import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;

/** review模块的私有待发布内容；跨模块仅保存申请与作者逻辑ID，不直接返回HTTP响应。 */
@Data
public class ReviewSubmissionEntity {
    private Long id;
    private Long restaurantRequestId;
    private Long originRequestId;
    private Long authorUserId;
    private String status;
    private BigDecimal tasteScore;
    private BigDecimal valueScore;
    private BigDecimal serviceScore;
    private BigDecimal ambienceScore;
    private ReviewRecommendation recommendation;
    @ToString.Exclude private String body;
    private String clientRequestId;
    private String requestHash;
    private String contentHash;
    private String publishRequestId;
    private Long publishedReviewId;
}
