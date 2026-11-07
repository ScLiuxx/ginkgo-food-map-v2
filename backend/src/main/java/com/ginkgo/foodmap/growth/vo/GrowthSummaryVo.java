package com.ginkgo.foodmap.growth.vo;

import lombok.Data;

/** 本人经验摘要；最高级的下一等级门槛为null，用户ID保持字符串协议。 */
@Data
public class GrowthSummaryVo {
    private String userId;
    private long experience;
    private int level;
    private long currentLevelThreshold;
    private Long nextLevelThreshold;
}
