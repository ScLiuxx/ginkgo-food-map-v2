package com.ginkgo.foodmap.restaurant.dto;

import lombok.Data;

/** 一次数据库快照中的Outbox存量；年龄从任务创建算起，重试等待时间也包含在内。 */
@Data
public class RestaurantSearchSyncSnapshot {
    private long pendingTasks;
    private long failedTasks;
    private long dueTasks;
    private long retryPendingTasks;
    private double oldestPendingAgeSeconds;
}
