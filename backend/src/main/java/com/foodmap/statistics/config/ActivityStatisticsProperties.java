package com.foodmap.statistics.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 采集显式开启；关闭后仍可读取已保存汇总，不重置首次启用时间。 */
@Data
@ConfigurationProperties("app.activity")
public class ActivityStatisticsProperties {
    private boolean enabled;
}
