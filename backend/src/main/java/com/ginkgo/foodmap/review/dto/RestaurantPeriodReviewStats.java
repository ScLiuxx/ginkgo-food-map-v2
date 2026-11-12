package com.ginkgo.foodmap.review.dto;

import lombok.Data;

import java.math.BigDecimal;

/** review对外的近期去重计分结果；仅暴露餐厅ID、人数及十分制原始和，不输出用户ID或正文。 */
@Data
public class RestaurantPeriodReviewStats {
    private long restaurantId;
    private long participantCount;
    private BigDecimal tasteSum;
    private BigDecimal valueSum;
    private BigDecimal serviceSum;
    private BigDecimal ambienceSum;
}
