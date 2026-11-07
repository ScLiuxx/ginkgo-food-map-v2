package com.ginkgo.foodmap.review.entity;

import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;

/** review私有持久化对象；不直接返回HTTP，幂等材料和正文不进入toString。 */
@Data
public class ReviewEntity {
    private Long id;
    private long restaurantId;
    private long authorUserId;
    private BigDecimal tasteScore;
    private BigDecimal valueScore;
    private BigDecimal serviceScore;
    private BigDecimal ambienceScore;
    private BigDecimal overallScore;
    private boolean recommended;
    @ToString.Exclude private String body;
    @ToString.Exclude private String clientRequestId;
    @ToString.Exclude private String requestHash;
    private Instant createdAt;
}
