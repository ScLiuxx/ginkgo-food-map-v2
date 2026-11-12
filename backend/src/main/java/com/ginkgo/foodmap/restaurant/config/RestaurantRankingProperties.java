package com.ginkgo.foodmap.restaurant.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.math.BigDecimal;

/** 榜单计算与读取参数；数据年龄从统计截止时间计算，不能因读取或回填缓存重新计时。 */
@Data
@Validated
@ConfigurationProperties("app.ranking")
public class RestaurantRankingProperties {
    /** 默认启动后异步生成并周期刷新；关闭只停自动任务，仍可读取最近成功快照。 */
    private boolean generationEnabled = true;
    /** 上一轮完成后等待的间隔，失败也按此间隔重试；不使用固定频率追赶积压。 */
    @NotNull
    private Duration refreshInterval = Duration.ofHours(1);

    /** GCJ-02既有清水河参考点；不影响目录/地图的原有位置配置。 */
    @Valid
    @NotNull
    private Campus qingshuihe = campus("30.754400", "103.931200");
    /** 历史沙河OSM参考点WGS84转换为GCJ-02，精度与商户DECIMAL坐标保持六位。 */
    @Valid
    @NotNull
    private Campus shahe = campus("30.675789", "104.099671");

    /** 每个校区独立配置中心与半径，生成时复制，整轮不混用可变配置对象。 */
    @Data
    public static class Campus {
        @NotNull
        @DecimalMin("-90")
        @DecimalMax("90")
        private BigDecimal latitude;
        @NotNull
        @DecimalMin("-180")
        @DecimalMax("180")
        private BigDecimal longitude;
        @Positive
        private double radiusMeters = 5000;
    }

    private static Campus campus(String latitude, String longitude) {
        var campus = new Campus();
        campus.setLatitude(new BigDecimal(latitude));
        campus.setLongitude(new BigDecimal(longitude));
        return campus;
    }

    /** 最近成功结果的展示上限；首版为24小时，与未来缓存条目的TTL独立。 */
    @NotNull
    private Duration maxSnapshotAge = Duration.ofHours(24);

    /** 关闭仅用于分阶段对照；本机命中跳过Redis及全局加载锁。 */
    private boolean caffeineEnabled = true;
    /** 写入后过期，不因访问续期；准备中及数据年龄另有更短上限。 */
    @NotNull
    private Duration caffeineTtl = Duration.ofMinutes(30);

    /** 仅用于分阶段对照或显式关闭榜单缓存，不改变认证Redis会话。 */
    private boolean redisEnabled = true;
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,159}:")
    private String redisKeyPrefix = "ginkgo:restaurant-rankings:v1:";
    @NotNull
    private Duration redisTtl = Duration.ofHours(2);
    /** 尚未生成仅短时缓存，它不是成功空榜。 */
    @NotNull
    private Duration preparingTtl = Duration.ofSeconds(5);
    @NotNull
    private Duration lockWait = Duration.ofSeconds(1);
    @NotNull
    private Duration redisCommandTimeout = Duration.ofMillis(200);
    @NotNull
    private Duration redisConnectTimeout = Duration.ofMillis(200);
    @NotNull
    private Duration repairDelay = Duration.ofSeconds(5);
    @NotNull
    private Duration repairCooldown = Duration.ofMinutes(1);
    @Min(1)
    private int repairAttempts = 3;

    /** 启动时拒绝非正时长，避免配置错误使榜单永不过期或立即失效。 */
    @AssertTrue(message = "榜单最大数据年龄必须大于零")
    public boolean isMaxSnapshotAgePositive() {
        return maxSnapshotAge != null && maxSnapshotAge.isPositive();
    }

    /** JDK调度采用毫秒单位，不能将过小间隔截断为零；即使关闭也拒绝非法配置。 */
    @AssertTrue(message = "榜单刷新间隔须至少1毫秒")
    public boolean isRefreshIntervalValid() {
        return refreshInterval != null && refreshInterval.compareTo(Duration.ofMillis(1)) >= 0;
    }

    /** 毫秒级Redis TTL和超时不能截断为零，故障重试分轮限速。 */
    @AssertTrue(message = "榜单缓存时长须至少1毫秒，准备中TTL不能超过正式TTL或最大数据年龄")
    public boolean isCacheTimingValid() {
        return java.util.stream.Stream.of(caffeineTtl, redisTtl, preparingTtl, lockWait, redisCommandTimeout,
                        redisConnectTimeout, repairDelay, repairCooldown)
                .allMatch(value -> value != null && value.toMillis() >= 1)
                && maxSnapshotAge != null && preparingTtl.compareTo(redisTtl) <= 0
                && preparingTtl.compareTo(maxSnapshotAge) <= 0;
    }
}
