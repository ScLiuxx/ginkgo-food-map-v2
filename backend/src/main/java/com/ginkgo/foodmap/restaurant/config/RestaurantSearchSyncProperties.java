package com.ginkgo.foodmap.restaurant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 单实例搜索同步参数；默认关闭，调整参数不会自动重放FAILED任务。 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.search.sync")
public class RestaurantSearchSyncProperties {
    private boolean enabled = false;
    /** 有限批次逐条执行，避免一次占用调度线程太久。 */
    @Min(1) @Max(100)
    private int batchSize = 20;
    @Min(1) @Max(100)
    private int maxAttempts = 5;
    @Min(100) @Max(86400000)
    private long delayMs = 5000;
    @Min(100) @Max(86400000)
    private long retryDelayMs = 30000;
    /** 仅写已经存在的独立别名，不自动建索引或回填历史数据。 */
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,127}")
    private String indexAlias = "restaurant-search";
}
