package com.foodmap.restaurant.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * 仅写入服务可读取的归属和删除状态，绝不直接返回给Controller调用者。
 * 更新SQL只写业务白名单，不允许覆盖来源或创建者；同一主行锁保护标签整体替换。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantManagementEntity extends RestaurantEntity {
    private Long createdBy;
    private Long updatedBy;
    private Instant deletedAt;
}
