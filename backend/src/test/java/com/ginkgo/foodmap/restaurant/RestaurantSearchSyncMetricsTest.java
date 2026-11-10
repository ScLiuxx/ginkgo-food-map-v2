package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchSyncSnapshot;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchSyncMetrics;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** 验证未知/陈旧快照、指标失败隔离和固定计数口径，抓取Gauge不能访问数据库。 */
class RestaurantSearchSyncMetricsTest {
    private final RestaurantSearchOutboxMapper tasks = mock(RestaurantSearchOutboxMapper.class);
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final RestaurantSearchSyncMetrics metrics =
            new RestaurantSearchSyncMetrics(meters, tasks, new RestaurantSearchSyncProperties());

    @Test
    void snapshotIsUnknownUntilFirstRefreshAndGaugesDoNotQueryDatabase() {
        metrics.initialize();
        assertThat(
                        meters.get("restaurant.search.sync.queue.tasks")
                                .tag("status", "pending")
                                .gauge()
                                .value())
                .isNaN();
        verifyNoInteractions(tasks);
        var data = new RestaurantSearchSyncSnapshot();
        data.setPendingTasks(8);
        data.setFailedTasks(2);
        data.setDueTasks(3);
        data.setRetryPendingTasks(4);
        data.setOldestPendingAgeSeconds(121.5);
        when(tasks.selectMetricsSnapshot(5)).thenReturn(data);
        clock.add(Duration.ofSeconds(30));
        metrics.refreshSnapshot();
        assertThat(
                        meters.get("restaurant.search.sync.queue.tasks")
                                .tag("status", "pending")
                                .gauge()
                                .value())
                .isEqualTo(8);
        assertThat(
                        meters.get("restaurant.search.sync.queue.tasks")
                                .tag("status", "failed")
                                .gauge()
                                .value())
                .isEqualTo(2);
        assertThat(meters.get("restaurant.search.sync.due.tasks").gauge().value()).isEqualTo(3);
        assertThat(meters.get("restaurant.search.sync.retry.pending.tasks").gauge().value())
                .isEqualTo(4);
        assertThat(meters.get("restaurant.search.sync.oldest.pending.age.seconds").gauge().value())
                .isEqualTo(121.5);
        verify(tasks, times(1)).selectMetricsSnapshot(5);
    }

    @Test
    void failedRefreshPreservesPreviousValuesAndTimestamp() {
        metrics.initialize();
        var data = new RestaurantSearchSyncSnapshot();
        data.setPendingTasks(3);
        when(tasks.selectMetricsSnapshot(5))
                .thenReturn(data)
                .thenThrow(new IllegalStateException("private database details"));
        clock.add(Duration.ofSeconds(30));
        metrics.refreshSnapshot();
        double timestamp =
                meters.get("restaurant.search.sync.snapshot.timestamp.seconds").gauge().value();
        clock.add(Duration.ofMinutes(1));
        metrics.refreshSnapshot();
        assertThat(meters.get("restaurant.search.sync.snapshot.timestamp.seconds").gauge().value())
                .isEqualTo(timestamp);
        assertThat(
                        meters.get("restaurant.search.sync.queue.tasks")
                                .tag("status", "pending")
                                .gauge()
                                .value())
                .isEqualTo(3);
        assertThat(meters.get("restaurant.search.sync.snapshot.errors").counter().count())
                .isEqualTo(1);
        assertThat(
                        meters.getMeters().stream()
                                .flatMap(m -> m.getId().getTags().stream())
                                .map(Object::toString))
                .noneMatch(v -> v.contains("private"));
    }

    @Test
    void metricRegistryFailuresCannotEscapeIntoBusinessFlow() {
        var broken = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("metrics unavailable"));
        var disabled =
                new RestaurantSearchSyncMetrics(
                        broken, tasks, new RestaurantSearchSyncProperties());
        disabled.task("success", true, System.nanoTime());
        disabled.batch(true);
        disabled.stateUpdateFailure(true);
        disabled.versionConflict();
    }
}
