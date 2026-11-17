package com.foodmap.review.vo;

import lombok.Data;

/** 联合提交只返回私有待发布回执，不返回正式点评ID、作者、内部摘要或预留发布UUID。 */
@Data
public class ReviewSubmissionReceipt {
    private String id;
    private String status;
}
