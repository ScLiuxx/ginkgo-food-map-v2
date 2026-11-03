package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;

/** 仅供本模块持久化使用的待审核快照，不直接作为公开响应。 */
@Data
public class RestaurantChangeEntity {
    private Long id;
    private Long restaurantId;
    private Long submittedBy;
    private String clientRequestId;
    private byte[] requestHash;
    private String proposedData;
    private String status;
}
