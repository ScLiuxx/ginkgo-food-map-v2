package com.foodmap.restaurant.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foodmap.review.vo.ReviewSubmissionReceipt;

import lombok.Data;

/** 保留原新店申请回执字段；仅联合提交额外返回待发布点评回执，不意味着公开发布完成。 */
@Data
public class RestaurantSubmissionVo {
    private String id;
    private String status;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ReviewSubmissionReceipt reviewSubmission;
}
