package com.ginkgo.foodmap.restaurant.metrics;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchSyncSnapshot;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 单实例Outbox消费观测：内存事件指标和定时数据库存量快照，采集端点不执行SQL。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true")
public class RestaurantSearchSyncMetrics {
    private final MeterRegistry meters;
    private final RestaurantSearchOutboxMapper tasks;
    private final RestaurantSearchSyncProperties properties;
    private volatile RestaurantSearchSyncSnapshot snapshot;
    private volatile double snapshotTimestamp;
    private volatile double lastBatchSuccessTimestamp;

    /** 固定标签提前注册为0；快照未成功前使用NaN，不能把未知积压伪装成空队列。 */
    @PostConstruct
    public void initialize() {
        safe(
                () -> {
                    Gauge.builder("restaurant.search.sync.enabled", () -> 1).register(meters);
                    for (String status : new String[] {"pending", "failed"}) {
                        Gauge.builder(
                                        "restaurant.search.sync.queue.tasks",
                                        this,
                                        state ->
                                                state.snapshot == null
                                                        ? Double.NaN
                                                        : "pending".equals(status)
                                                                ? state.snapshot.getPendingTasks()
                                                                : state.snapshot.getFailedTasks())
                                .tag("status", status)
                                .register(meters);
                    }
                    Gauge.builder(
                                    "restaurant.search.sync.due.tasks",
                                    this,
                                    state ->
                                            state.snapshot == null
                                                    ? Double.NaN
                                                    : state.snapshot.getDueTasks())
                            .register(meters);
                    Gauge.builder(
                                    "restaurant.search.sync.retry.pending.tasks",
                                    this,
                                    state ->
                                            state.snapshot == null
                                                    ? Double.NaN
                                                    : state.snapshot.getRetryPendingTasks())
                            .register(meters);
                    Gauge.builder(
                                    "restaurant.search.sync.oldest.pending.age.seconds",
                                    this,
                                    state ->
                                            state.snapshot == null
                                                    ? Double.NaN
                                                    : state.snapshot.getOldestPendingAgeSeconds())
                            .register(meters);
                    Gauge.builder(
                                    "restaurant.search.sync.snapshot.timestamp.seconds",
                                    this,
                                    state -> state.snapshotTimestamp)
                            .register(meters);
                    Gauge.builder(
                                    "restaurant.search.sync.last.batch.success.timestamp.seconds",
                                    this,
                                    state -> state.lastBatchSuccessTimestamp)
                            .register(meters);
                    for (String outcome : new String[] {"success", "write_error", "ack_error"}) {
                        meters.counter("restaurant.search.sync.tasks", "outcome", outcome);
                        taskTimer(outcome);
                    }
                    for (String outcome : new String[] {"success", "error"}) {
                        meters.counter("restaurant.search.sync.batches", "outcome", outcome);
                    }
                    for (String state : new String[] {"success", "failure"}) {
                        meters.counter(
                                "restaurant.search.sync.state.update.failures", "state", state);
                    }
                    meters.counter("restaurant.search.sync.retries");
                    meters.counter("restaurant.search.sync.version.conflicts");
                    meters.counter("restaurant.search.sync.snapshot.errors");
                    meters.counter("restaurant.search.sync.groups");
                    meters.counter("restaurant.search.sync.coalesced.tasks");
                    for (String outcome : new String[] {"response", "error"}) {
                        meters.counter("restaurant.search.sync.bulk.requests", "outcome", outcome);
                        bulkTimer(outcome);
                    }
                    for (String outcome : new String[] {"success", "error"}) {
                        meters.counter("restaurant.search.sync.bulk.items", "outcome", outcome);
                    }
                    meters.counter("restaurant.search.sync.bulk.rejected.items");
                    bulkSummary("documents");
                    bulkSummary("bytes");
                });
    }

    /** 汇总异常时保留上次值及时间戳，调用方可据新鲜度识别陈旧快照；不影响消费。 */
    @Scheduled(
            fixedDelayString = "${app.search.sync.metrics-delay-ms:30000}",
            initialDelayString = "${app.search.sync.metrics-delay-ms:30000}")
    public void refreshSnapshot() {
        try {
            var current =
                    Objects.requireNonNull(
                            tasks.selectMetricsSnapshot(properties.getMaxAttempts()));
            snapshot = current;
            snapshotTimestamp = meters.config().clock().wallTime() / 1000.0;
        } catch (RuntimeException failure) {
            safe(() -> meters.counter("restaurant.search.sync.snapshot.errors").increment());
        }
    }

    /** success须包含数据库确认；重试只统计已有持久失败次数的再次消费，不推断所有幂等重放。 */
    public void task(String outcome, boolean retry, long started) {
        safe(
                () -> {
                    meters.counter("restaurant.search.sync.tasks", "outcome", outcome).increment();
                    taskTimer(outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    if (retry) {
                        meters.counter("restaurant.search.sync.retries").increment();
                    }
                });
    }

    /** 批次成功仅表示完成扫描和逐条处理，不表示批内所有任务写入成功；空批次也更新心跳。 */
    public void batch(boolean success) {
        safe(
                () -> {
                    meters.counter(
                                    "restaurant.search.sync.batches",
                                    "outcome",
                                    success ? "success" : "error")
                            .increment();
                    if (success) {
                        lastBatchSuccessTimestamp = meters.config().clock().wallTime() / 1000.0;
                    }
                });
    }

    /** 状态更新抛错或影响行数不为1均统计；state为原本希望写入的成功/失败状态。 */
    public void stateUpdateFailure(boolean success) {
        safe(
                () ->
                        meters.counter(
                                        "restaurant.search.sync.state.update.failures",
                                        "state",
                                        success ? "success" : "failure")
                                .increment());
    }

    /** ES返回409的次数；包含同版本相同正文及更新版本覆盖，不等同于丢失数据或最终失败。 */
    public void versionConflict() {
        safe(() -> meters.counter("restaurant.search.sync.version.conflicts").increment());
    }

    /** 每批每家餐厅只处理一组，合并数为组内任务数减1；这是逻辑合并量而非实测HTTP节省量。 */
    public void group(int tasks) {
        safe(
                () -> {
                    meters.counter("restaurant.search.sync.groups").increment();
                    meters.counter("restaurant.search.sync.coalesced.tasks")
                            .increment(Math.max(0, tasks - 1));
                });
    }

    /** 请求收到完整条数响应与传输/协议异常分别计数；response可能包含失败item。 */
    public void bulkRequest(boolean response, int documents, long bytes, long started) {
        safe(
                () -> {
                    String outcome = response ? "response" : "error";
                    meters.counter("restaurant.search.sync.bulk.requests", "outcome", outcome)
                            .increment();
                    bulkTimer(outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    bulkSummary("documents").record(documents);
                    bulkSummary("bytes").record(bytes);
                });
    }

    /** 实际发出的文档最终处理结果，包含409核对与传输异常；不包含发送前的组装/大小校验失败。 */
    public void bulkItem(boolean success) {
        safe(
                () ->
                        meters.counter(
                                        "restaurant.search.sync.bulk.items",
                                        "outcome",
                                        success ? "success" : "error")
                                .increment());
    }

    /** 仅统计Bulk响应中明确返回429的item，区别于请求级结果未知或其他写入失败。 */
    public void bulkRejectedItem() {
        safe(() -> meters.counter("restaurant.search.sync.bulk.rejected.items").increment());
    }

    private Timer bulkTimer(String outcome) {
        return Timer.builder("restaurant.search.sync.bulk.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meters);
    }

    private DistributionSummary bulkSummary(String dimension) {
        return DistributionSummary.builder("restaurant.search.sync.bulk." + dimension)
                .register(meters);
    }

    private Timer taskTimer(String outcome) {
        return Timer.builder("restaurant.search.sync.task.duration")
                .description(
                        "Projection assembly, ES write and outbox acknowledgement per task attempt")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meters);
    }

    private void safe(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // 指标注册/写入失败不能改变任务确认、重试、版本校验或互斥语义。
        }
    }
}
