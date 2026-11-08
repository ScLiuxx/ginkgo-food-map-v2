package com.ginkgo.foodmap.review.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** 点评读取投影，不读取UUID/摘要等发布材料；只在review模块内部使用。 */
@Data
public class ReviewReadEntity {
    private Long id;

    /** 本人跨店分组读取时使用；商户视角查询不需要填充，不改变公开点评字段。 */
    private Long restaurantId;

    private Long windowId;

    private long authorUserId;
    private BigDecimal tasteScore;
    private BigDecimal valueScore;
    private BigDecimal serviceScore;
    private BigDecimal ambienceScore;
    private BigDecimal overallScore;
    private boolean recommended;
    @lombok.ToString.Exclude private String body;
    private Instant createdAt;
    private long historyCount;
}
