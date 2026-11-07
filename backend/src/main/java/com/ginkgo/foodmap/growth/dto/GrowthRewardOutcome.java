package com.ginkgo.foodmap.growth.dto;

/** 内部协作的幂等结果；已撤销的奖励资格不会自动恢复，不映射为额外HTTP写接口。 */
public enum GrowthRewardOutcome {
    GRANTED, ALREADY_GRANTED, REVOKED, ALREADY_REVOKED, NOT_FOUND
}
