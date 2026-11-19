package com.foodmap.statistics.policy;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** 统计独立的可替换时间依赖，不改变榜单的Clock装配。 */
@Component
public class ActivityTime {
    public Instant now() {
        return Instant.now();
    }
}
