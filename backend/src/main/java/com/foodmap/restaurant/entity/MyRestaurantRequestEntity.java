package com.foodmap.restaurant.entity;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

/** 本人申请内部投影；原UUID仅供锁定写入流程使用，HTTP响应仍经显式白名单转换。 */
@Data
public class MyRestaurantRequestEntity {
    private Long id;
    private Long restaurantId;

    /** 驳回后重提所依据的原申请ID；本人详情展示关联，普通提交为空。 */
    private Long previousRequestId;

    private String requestType;
    private String status;

    /** 通过处理方式：CREATED、UPDATED、LINKED_EXISTING；未通过或历史记录可为空。 */
    private String approvalMode;

    /** 仅SELECT *的写入锁读取，详情查询不选择该字段，不向本人详情响应暴露。 */
    @ToString.Exclude private String clientRequestId;

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
