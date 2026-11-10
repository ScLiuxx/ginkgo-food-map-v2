package com.ginkgo.foodmap.restaurant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 单实例搜索同步参数；默认关闭，调整参数不会自动重放FAILED任务。 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.search.sync")
public class RestaurantSearchSyncProperties {
    private boolean enabled = false;

    /** 每批捕获的任务条数上限，按餐厅分组后共享投影读取和ES写入。 */
    @Min(1)
    @Max(100)
    private int batchSize = 20;

    /** 首次空闲唤醒后的固定合并窗口，后续通知不延长；满批积压连续处理不重复等待。 */
    @Min(0)
    @Max(5000)
    private long coalesceWindowMs = 200;

    /** Bulk请求的UTF-8 NDJSON大小上限；超过单文档上限明确失败，避免无限拆分。 */
    @Min(1024)
    @Max(10485760)
    private int bulkMaxBytes = 1048576;

    @Min(1)
    @Max(100)
    private int maxAttempts = 5;

    /** 提交后通知负责及时消费；30秒兜底恢复丢失通知、重启遗留和到期重试。 */
    @Min(100)
    @Max(86400000)
    private long delayMs = 30000;

    @Min(100)
    @Max(86400000)
    private long retryDelayMs = 30000;

    /** 观测独立于消费调度；定期查询缓存快照，不在Prometheus抓取线程访问数据库。 */
    @Min(1000)
    @Max(86400000)
    private long metricsDelayMs = 30000;

    /** 仅写已经存在的独立别名，不自动建索引或回填历史数据。 */
    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,127}")
    private String indexAlias = "restaurant-search";
}
