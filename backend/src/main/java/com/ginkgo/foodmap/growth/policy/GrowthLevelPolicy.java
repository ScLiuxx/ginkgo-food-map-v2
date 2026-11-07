package com.ginkgo.foodmap.growth.policy;

import com.ginkgo.foodmap.growth.config.GrowthProperties;
import com.ginkgo.foodmap.growth.vo.GrowthSummaryVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 从当前净经验计算等级的纯规则；查询不写等级字段，也不扣除升级消耗。 */
@Component
@RequiredArgsConstructor
public class GrowthLevelPolicy {
    private final GrowthProperties properties;

    /** 复制本次使用的门槛集合，确保一次响应的当前/下一门槛来自同一份配置。 */
    public GrowthSummaryVo summarize(long userId, long experience) {
        if (userId <= 0 || experience < 0) throw new IllegalArgumentException("Invalid growth identity or experience");
        if (!properties.isThresholdsValid()) throw new IllegalStateException("Invalid growth thresholds");
        var thresholds = List.copyOf(properties.getLevelThresholds());
        int index = 0;
        while (index + 1 < thresholds.size() && experience >= thresholds.get(index + 1)) index++;
        var result = new GrowthSummaryVo();
        result.setUserId(Long.toString(userId));
        result.setExperience(experience);
        result.setLevel(index + 1);
        result.setCurrentLevelThreshold(thresholds.get(index));
        result.setNextLevelThreshold(index + 1 < thresholds.size() ? thresholds.get(index + 1) : null);
        return result;
    }
}
