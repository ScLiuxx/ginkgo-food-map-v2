package com.ginkgo.foodmap.restaurant.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/** 进程内唯一ES查询熔断器：列表和地图共享；状态重启清空，不关联同步消费者。 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@Slf4j
public class RestaurantSearchCircuitBreakerConfiguration {
    private final RestaurantSearchQueryProperties properties;

    /** 只按失败率判定；等待到期后的真实请求负责半开，不主动探测ES。 */
    @Bean
    public CircuitBreaker restaurantEsQueryCircuitBreaker(MeterRegistry meters) {
        var settings = properties.getCircuitBreaker();
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                // 本轮不启用独立慢调用熔断：使用纳秒计时可表达的最大阈值，超时仍作为异常计数。
                .slowCallDurationThreshold(Duration.ofNanos(Long.MAX_VALUE))
                .build();
        var breaker = CircuitBreaker.of("restaurant-es-query", config);
        var success = meters.counter("restaurant.search.es.calls", "outcome", "success");
        var failure = meters.counter("restaurant.search.es.calls", "outcome", "failure");
        var rejected = meters.counter("restaurant.search.es.calls", "outcome", "rejected");
        breaker.getEventPublisher()
                .onSuccess(event -> success.increment())
                .onError(event -> failure.increment())
                .onCallNotPermitted(event -> rejected.increment())
                .onStateTransition(event -> log.info("ES查询熔断状态变化：{}", event.getStateTransition()));
        // 仅注册进既有Actuator指标体系，不新增公开端点；0正常、1打开、2半开、3其他状态。
        Gauge.builder("restaurant.search.es.circuit.state", breaker, value -> switch (value.getState()) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 3;
        }).register(meters);
        return breaker;
    }
}
