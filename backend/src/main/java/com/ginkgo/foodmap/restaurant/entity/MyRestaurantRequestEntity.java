package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

/** 本人申请最小投影；不读取幂等摘要、原始UUID、审核人或纠错前的管理快照。 */
@Data
public class MyRestaurantRequestEntity {
    private Long id;
    private Long restaurantId;
    /** 驳回后重提所依据的原申请ID；本人详情展示关联，普通提交为空。 */
    private Long previousRequestId;
    private String requestType;
    private String status;
    private String requestName;
    private Instant createdAt;
    private Instant reviewedAt;
    /** 撤回时间；仅WITHDRAWN有值，撤回不会写入审核字段。 */
    private Instant withdrawnAt;
    private int photoCount;
    @ToString.Exclude private String proposedData;
    @ToString.Exclude private String approvedData;
    @ToString.Exclude private String rejectReason;
}
