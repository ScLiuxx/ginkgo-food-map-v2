package com.ginkgo.foodmap.growth.entity;

import lombok.Data;
import java.time.Instant;

/** 成长模块自有账户映射；不作为HTTP响应，也不向其他模块暴露持久化对象。 */
@Data
public class GrowthAccountEntity {
    private long userId;
    private long experience;
    private Instant updatedAt;
}
