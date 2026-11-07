package com.ginkgo.foodmap.review.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 历史每次默认5条、最多20条；游标格式及归属由读取规则进一步验证。 */
@Data
public class ReviewHistoryQuery {
    @NotNull
    @Min(1)
    @Max(20)
    private Integer pageSize = 5;

    @Size(max = 200)
    private String cursor;
}
