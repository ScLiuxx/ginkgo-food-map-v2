package com.ginkgo.foodmap.restaurant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 查询独立于同步开关；默认关闭，允许先验证读链路再显式启用。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.search.query")
public class RestaurantSearchQueryProperties {
    private boolean enabled = false;

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,127}")
    private String indexAlias = "restaurant-search";

    /** 仅约束查询侧ES调用，独立于MySQL回查与Outbox写入；配置变更随进程重启生效。 */
    @Valid @NotNull private CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings();

    /** 采用请求数窗口；半开由真实请求触发，不创建后台探活线程。 */
    @Data
    public static class CircuitBreakerSettings {
        @Min(1)
        private int slidingWindowSize = 20;

        @Min(1)
        private int minimumNumberOfCalls = 10;

        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("100")
        private float failureRateThreshold = 50;

        @NotNull private Duration waitDurationInOpenState = Duration.ofSeconds(10);

        @Min(1)
        private int permittedNumberOfCallsInHalfOpenState = 1;

        /** 防止库静默压低最小样本数，导致实际保护规则与配置声明不一致。 */
        @AssertTrue(message = "熔断最小样本数不能大于统计窗口")
        public boolean isWindowValid() {
            return minimumNumberOfCalls <= slidingWindowSize;
        }

        /** Resilience4j以毫秒保存打开等待时间，禁止零、负值及不足一毫秒的配置。 */
        @AssertTrue(message = "熔断等待时间必须至少1毫秒")
        public boolean isWaitDurationValid() {
            return waitDurationInOpenState == null
                    || (!waitDurationInOpenState.isNegative()
                            && waitDurationInOpenState.compareTo(Duration.ofMillis(1)) >= 0);
        }
    }
}
