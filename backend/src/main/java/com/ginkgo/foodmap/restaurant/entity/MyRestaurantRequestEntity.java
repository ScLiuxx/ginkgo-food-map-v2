package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

/** 本人申请最小投影；不读取幂等摘要、原始UUID、审核人或纠错前的管理快照。 */
@Data
public class MyRestaurantRequestEntity {
    private Long id;
    private Long restaurantId;
    private String requestType;
    private String status;
    private String requestName;
    private Instant createdAt;
    private Instant reviewedAt;
    private int photoCount;
    @ToString.Exclude private String proposedData;
    @ToString.Exclude private String approvedData;
    @ToString.Exclude private String rejectReason;
}
