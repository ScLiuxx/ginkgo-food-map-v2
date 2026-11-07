package com.ginkgo.foodmap.growth;

import com.ginkgo.foodmap.growth.config.GrowthProperties;
import com.ginkgo.foodmap.growth.policy.GrowthLevelPolicy;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证等级边界及配置约束，不访问数据库，不把相同实现重复写为测试断言。 */
class GrowthLevelPolicyTest {
    @ParameterizedTest
    @CsvSource({"0,1,0,100", "99,1,0,100", "100,2,100,400", "399,2,100,400", "400,3,400,1000", "999,3,400,1000", "1000,4,1000,null", "9223372036854775807,4,1000,null"})
    void calculatesCumulativeThresholdBoundaries(long experience, int level, long current, String next) {
        var summary = new GrowthLevelPolicy(new GrowthProperties()).summarize(11, experience);
        assertThat(summary.getUserId()).isEqualTo("11");
        assertThat(summary.getExperience()).isEqualTo(experience);
        assertThat(summary.getLevel()).isEqualTo(level);
        assertThat(summary.getCurrentLevelThreshold()).isEqualTo(current);
        assertThat(summary.getNextLevelThreshold()).isEqualTo("null".equals(next) ? null : Long.valueOf(next));
    }

    /** 配置允许部署时调整，但零起点、递增和正奖励必须在启动验证中成立。 */
    @Test void validatesConfigurationAndRejectsInvalidDomainValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var properties = new GrowthProperties();
            assertThat(validator.validate(properties)).isEmpty();
            for (var thresholds : List.of(List.<Long>of(), List.of(1L,100L), List.of(0L,100L,100L), List.of(0L,100L,50L), Arrays.asList(0L,null))) {
                properties.setLevelThresholds(thresholds);
                assertThat(validator.validate(properties)).isNotEmpty();
            }
            properties.setLevelThresholds(null);
            assertThat(validator.validate(properties)).isNotEmpty();
            properties.setLevelThresholds(List.of(0L,100L));
            properties.setFirstReviewExperience(0);
            assertThat(validator.validate(properties)).isNotEmpty();
        }
        var policy = new GrowthLevelPolicy(new GrowthProperties());
        assertThrows(IllegalArgumentException.class, () -> policy.summarize(0,0));
        assertThrows(IllegalArgumentException.class, () -> policy.summarize(11,-1));
    }
}
