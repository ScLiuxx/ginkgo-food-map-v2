package com.ginkgo.foodmap.growth.config;

import jakarta.validation.constraints.*;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** 成长规则随部署配置加载；旧奖励的实际amount独立保存，调参不回算历史奖励。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.growth")
public class GrowthProperties {
    /** 每用户每店首次有效点评奖励，正整数范围与V24 amount列一致。 */
    @Min(1)
    private int firstReviewExperience = 5;

    /** 审核贡献奖励；已发奖励不随配置变化重算。 */
    @Min(1)
    private int newStoreExperience = 20;

    @Min(1)
    private int correctionExperience = 10;

    /** 点评获赞奖励及作者日总额度；额度不足不影响正常点赞。 */
    @Min(1)
    private int likeExperience = 1;

    @Min(1)
    private int dailyLikeExperienceLimit = 5;

    /** 从初始等级开始的累计门槛；等级不单独写入数据库。 */
    @NotEmpty
    private List<@NotNull @PositiveOrZero Long> levelThresholds = List.of(0L, 100L, 400L, 1000L);

    /** 门槛必须从零开始且严格递增，禁止因重复或乱序配置产生含糊等级。 */
    @AssertTrue(message = "成长等级门槛必须从0开始且严格递增")
    public boolean isThresholdsValid() {
        if (levelThresholds == null
                || levelThresholds.isEmpty()
                || !Long.valueOf(0).equals(levelThresholds.getFirst())) {
            return false;
        }
        for (int i = 1; i < levelThresholds.size(); i++) {
            if (levelThresholds.get(i) == null
                    || levelThresholds.get(i - 1) == null
                    || levelThresholds.get(i) <= levelThresholds.get(i - 1)) {
                return false;
            }
        }
        return true;
    }
}
