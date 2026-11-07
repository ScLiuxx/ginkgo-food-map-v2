package com.ginkgo.foodmap.growth.entity;

import lombok.Data;
import java.time.Instant;

/** V24奖励记录；原来源、amount与发奖时间写入后不通过本模块更新。 */
@Data
public class GrowthRewardEntity {
    private Long id;
    private long userId;
    private String rewardType;
    private String businessKey;
    private String sourceType;
    private long sourceId;
    private int amount;
    private String status;
    private Instant grantedAt;
    private Instant revokedAt;
    private Long dispositionId;
    private Long actorUserId;
    private java.time.LocalDate businessDate;
}
