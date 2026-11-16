package com.ginkgo.foodmap.notification.dto;

import lombok.Data;

/** 商户模块确认并锁定审核事实后提供的最小快照；不是客户端请求，不接收任意跳转URL。 */
@Data
public class RestaurantDecisionNotification {
    private long recipientUserId;
    private long requestId;
    private String requestType;
    private String decision;

    /** 通过处理方式：CREATED、UPDATED、LINKED_EXISTING；未通过或历史记录可为空。 */
    private String approvalMode;

    private String restaurantName;
    private String rejectReason;
}
