package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import java.time.Instant;

/** 仅供本模块持久化使用的待审核快照，不直接作为公开响应。 */
@Data
public class RestaurantChangeEntity {
    private Long id;
    private Long restaurantId;
    /** 驳回后重提指向的原申请ID；普通首次提交为空，用于保留新旧申请关联。 */
    private Long previousRequestId;
    /** CREATE/UPDATE由提交入口确定；新店通过回填商户ID后仍保持CREATE。 */
    private String requestType;
    private Long submittedBy;
    private String clientRequestId;
    private byte[] requestHash;
    private String proposedData;
    private String status;
    private Instant createdAt;
    /** 审核写入字段；用户原始proposedData与摘要保持不变，终态不可再次改写。 */
    private Long reviewedBy;
    private Instant reviewedAt;
    /** 本人撤回待审申请的时间；撤回不写审核人和通过/驳回资料。 */
    private Instant withdrawnAt;
    private String rejectReason;
    private String approvedData;
    private String beforeData;
}
