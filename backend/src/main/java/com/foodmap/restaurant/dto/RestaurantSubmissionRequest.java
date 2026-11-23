package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foodmap.review.dto.ReviewSubmissionRequest;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 新店和驳回重提接受可选点评；纠错入口继续使用原DTO，不能借新增字段评价食堂或纠错目标。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantSubmissionRequest extends RestaurantChangeRequest {
    /** 点评只写入review模块；新投稿缺省为不附带，重提缺省为沿用已有点评。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private ReviewSubmissionRequest review;
}
