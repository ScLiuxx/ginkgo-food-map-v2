package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ginkgo.foodmap.review.dto.ReviewSubmissionRequest;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 新店入口独立接受可选点评，纠错和驳回重提继续使用原DTO，不能借新增字段绕过生命周期校验。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantSubmissionRequest extends RestaurantChangeRequest {
    /** 点评只写入review模块，不进入店铺资料快照；缺省或null表示本次不附带点评。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private ReviewSubmissionRequest review;
}
