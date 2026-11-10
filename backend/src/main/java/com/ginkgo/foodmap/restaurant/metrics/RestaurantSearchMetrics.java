package com.ginkgo.foodmap.restaurant.metrics;

import com.ginkgo.foodmap.restaurant.dto.RestaurantListQuery;
import com.ginkgo.foodmap.restaurant.vo.RestaurantPageVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantSearchStatus;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 列表检索指标的固定口径；不记录用户输入，指标注册或写入异常不能影响检索结果。 */
public final class RestaurantSearchMetrics {
    private RestaurantSearchMetrics() {}

    /** 在服务退出时只记录一次；未返回结果为none，计划走ES包括客户端缺失及熔断拒绝。 */
    public static void list(
            MeterRegistry meters,
            RestaurantListQuery query,
            RestaurantSearchStatus status,
            RestaurantPageVo page,
            long started) {
        try {
            String source = page == null ? "none" : "ES".equals(status.getEngine()) ? "es" : "sql";
            String route =
                    status == null
                            ? "none"
                            : status.isDegraded() || "ES".equals(status.getEngine()) ? "es" : "sql";
            var tags =
                    Tags.of(
                            "query_type",
                            queryType(query),
                            "source",
                            source,
                            "outcome",
                            page == null ? "error" : "success",
                            "route",
                            route);
            meters.counter("restaurant.search.requests", tags).increment();
            timer(meters, "restaurant.search.duration", tags, started);
            if (page != null) {
                // 按完整匹配数量判断，翻到空页但仍有命中不算零结果。
                meters.counter(
                                "restaurant.search.results",
                                "query_type",
                                queryType(query),
                                "source",
                                source,
                                "empty",
                                page.getTotal() == 0 ? "true" : "false")
                        .increment();
            }
        } catch (RuntimeException ignored) {
            // 监控不可用时保留业务返回及原异常，不因观测失败触发降级。
        }
    }

    /** 已有降级计数增加scope，区分列表与地图；reason沿用有界的现行接口原因。 */
    public static void fallback(MeterRegistry meters, String reason, boolean map) {
        try {
            meters.counter(
                            "restaurant.search.fallbacks",
                            "reason",
                            reason,
                            "scope",
                            map ? "map" : "list")
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标失败不能中断SQL兜底。
        }
    }

    /** 统计实际进入的列表ES阶段；包含检查、分析、搜索、投影解析及失败前已发生的SDK调用。 */
    public static void es(
            MeterRegistry meters,
            RestaurantListQuery query,
            long started,
            int requests,
            Throwable failure) {
        try {
            var tags =
                    Tags.of(
                            "query_type",
                            queryType(query),
                            "outcome",
                            failure == null ? "success" : "error",
                            "reason",
                            reason(failure));
            timer(meters, "restaurant.search.es.duration", tags, started);
            DistributionSummary.builder("restaurant.search.es.requests.per.query")
                    .description("SDK call attempts per list ES stage; excludes transport retries")
                    .tags(tags)
                    .register(meters)
                    .record(requests);
        } catch (RuntimeException ignored) {
            // ES结果或原异常始终优先于指标。
        }
    }

    /** 调用SDK前记录一次尝试；不是底层HTTP报文数，客户端内部重试不重复计数。 */
    public static void request(MeterRegistry meters, RestaurantListQuery query, String operation) {
        try {
            meters.counter(
                            "restaurant.search.es.requests",
                            "query_type",
                            queryType(query),
                            "operation",
                            operation)
                    .increment();
        } catch (RuntimeException ignored) {
            // 无额外网络调用，不改变SDK执行。
        }
    }

    private static String queryType(RestaurantListQuery query) {
        return query == null || query.getKeyword().strip().isEmpty() ? "browse" : "keyword";
    }

    private static void timer(MeterRegistry meters, String name, Tags tags, long started) {
        Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meters)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private static String reason(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        // 只使用固定错误类别，禁止异常正文进入标签；遍历设上限以防异常链异常循环。
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException
                    || cause instanceof java.net.UnknownHostException) {
                return "connection";
            }
        }
        if (failure instanceof IOException) {
            return "io";
        }
        if (failure instanceof IllegalStateException
                || failure instanceof IllegalArgumentException) {
            return "invalid_response";
        }
        return "other";
    }
}
