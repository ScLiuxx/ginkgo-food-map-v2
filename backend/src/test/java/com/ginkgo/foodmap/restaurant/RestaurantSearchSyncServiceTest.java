package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchTask;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.metrics.RestaurantSearchSyncMetrics;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter.WriteResult;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchProjectionService;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 逐条失败隔离、确认故障和单JVM互斥，无外部数据库或ES依赖。 */
class RestaurantSearchSyncServiceTest {
    private final RestaurantSearchOutboxMapper tasks = mock(RestaurantSearchOutboxMapper.class);
    private final RestaurantSearchProjectionService projections =
            mock(RestaurantSearchProjectionService.class);
    private final RestaurantSearchIndexWriter writer = mock(RestaurantSearchIndexWriter.class);
    private final RestaurantSearchSyncProperties properties = new RestaurantSearchSyncProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestaurantSearchSyncMetrics metrics =
            new RestaurantSearchSyncMetrics(meters, tasks, properties);
    private final RestaurantSearchSyncService sync =
            new RestaurantSearchSyncService(tasks, projections, writer, properties, metrics);

    @BeforeEach
    void prepare() {
        when(writer.writeBatch(anyList()))
                .thenAnswer(invocation -> results(invocation.getArgument(0), null));
        when(tasks.selectDue(20, 5)).thenReturn(List.of(task(1, 101, 2, 0), task(2, 102, 1, 2)));
        when(projections.assemble(List.of(101L))).thenReturn(List.of(document(101, 3)));
        when(projections.assemble(List.of(102L))).thenReturn(List.of(document(102, 1)));
        when(tasks.succeed(anyLong(), anyInt())).thenReturn(1);
        when(tasks.fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString())).thenReturn(1);
    }

    @Test
    void writesFreshProjectionThenConfirmsOnlySpecificTasks() throws Exception {
        sync.processBatch();
        verify(writer).writeBatch(List.of(document(101, 3), document(102, 1)));
        verify(tasks).succeed(1, 0);
        verify(tasks).succeed(2, 2);
        verify(tasks, never()).fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(2);
        assertThat(meters.get("restaurant.search.sync.retries").counter().count()).isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.task.duration")
                                .tag("outcome", "success")
                                .timer()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void failedItemGetsRetryAndDoesNotBlockNextItem() throws Exception {
        when(writer.writeBatch(anyList()))
                .thenAnswer(
                        invocation ->
                                results(
                                        invocation.getArgument(0),
                                        new IOException("private source must not be recorded")));
        sync.processBatch();
        verify(tasks).fail(1, 0, 5, 30000, "IOException");
        verify(tasks, never()).succeed(1, 0);
        verify(tasks).succeed(2, 2);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "write_error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void failedAcknowledgementDoesNotTurnSuccessfulEsWriteIntoRetryFailure() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(tasks).succeed(1, 0);
        sync.processBatch();
        verify(tasks, never()).fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
        verify(tasks).succeed(2, 2);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "ack_error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.state.update.failures")
                                .tag("state", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    /** 影响行数异常与数据库抛错都必须暴露；任务失败仍不阻塞下一条。 */
    @Test
    void failedStatePersistenceIsObservedWithoutLosingOtherTasks() throws Exception {
        when(writer.writeBatch(anyList()))
                .thenAnswer(
                        invocation ->
                                results(invocation.getArgument(0), new IOException("unavailable")));
        when(tasks.fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString())).thenReturn(0);
        when(tasks.succeed(2, 2)).thenReturn(0);
        sync.processBatch();
        assertThat(
                        meters.get("restaurant.search.sync.state.update.failures")
                                .tag("state", "failure")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.state.update.failures")
                                .tag("state", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "ack_error")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    /** 扫描失败必须可见且释放互斥，后续空批次仍能完成并更新消费心跳。 */
    @Test
    void scanFailureIsCountedAndNextBatchCanRun() {
        when(tasks.selectDue(20, 5))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, sync::processBatch);
        sync.processBatch();
        assertThat(
                        meters.get("restaurant.search.sync.batches")
                                .tag("outcome", "error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.batches")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void groupsSameRestaurantButConfirmsEveryCapturedTask() throws Exception {
        when(tasks.selectDue(20, 5))
                .thenReturn(
                        List.of(
                                task(1, 101, 1, 0),
                                task(3, 101, 2, 1),
                                task(4, 101, 3, 0),
                                task(2, 102, 1, 2)));
        sync.processBatch();
        verify(projections, times(1)).assemble(List.of(101L));
        verify(writer, times(1)).writeBatch(List.of(document(101, 3), document(102, 1)));
        verify(tasks).succeed(1, 0);
        verify(tasks).succeed(3, 1);
        verify(tasks).succeed(4, 0);
        verify(tasks).succeed(2, 2);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(4);
        assertThat(meters.get("restaurant.search.sync.groups").counter().count()).isEqualTo(2);
        assertThat(meters.get("restaurant.search.sync.coalesced.tasks").counter().count())
                .isEqualTo(2);
    }

    @Test
    void groupRequiresProjectionCoveringHighestCapturedVersion() throws Exception {
        when(tasks.selectDue(20, 5))
                .thenReturn(List.of(task(1, 101, 1, 0), task(3, 101, 4, 2), task(2, 102, 1, 0)));
        sync.processBatch();
        verify(writer, never())
                .writeBatch(argThat(documents -> documents.contains(document(101, 3))));
        verify(tasks).fail(1, 0, 5, 30000, "IllegalStateException");
        verify(tasks).fail(3, 2, 5, 30000, "IllegalStateException");
        verify(tasks).succeed(2, 0);
    }

    @Test
    void failedGroupSharesOneWriteButPreservesIndividualRetryBudgets() throws Exception {
        when(tasks.selectDue(20, 5)).thenReturn(List.of(task(1, 101, 1, 0), task(3, 101, 2, 2)));
        when(writer.writeBatch(anyList()))
                .thenAnswer(
                        invocation ->
                                results(invocation.getArgument(0), new IOException("unavailable")));
        sync.processBatch();
        verify(writer, times(1)).writeBatch(List.of(document(101, 3)));
        verify(tasks).fail(1, 0, 5, 30000, "IOException");
        verify(tasks).fail(3, 2, 5, 30000, "IOException");
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "write_error")
                                .counter()
                                .count())
                .isEqualTo(2);
        assertThat(meters.get("restaurant.search.sync.retries").counter().count()).isEqualTo(1);
    }

    @Test
    void confirmationFailureDoesNotLoseOtherTasksInSameGroup() throws Exception {
        when(tasks.selectDue(20, 5)).thenReturn(List.of(task(1, 101, 1, 0), task(3, 101, 2, 1)));
        when(tasks.succeed(1, 0)).thenReturn(0);
        sync.processBatch();
        verify(writer, times(1)).writeBatch(List.of(document(101, 3)));
        verify(tasks).succeed(3, 1);
        verify(tasks, never()).fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "ack_error")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        meters.get("restaurant.search.sync.tasks")
                                .tag("outcome", "success")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void fullBatchContinuesOnlyWhenAtLeastOneTaskWasDurablyAdvanced() {
        properties.setBatchSize(2);
        when(tasks.selectDue(2, 5)).thenReturn(List.of(task(1, 101, 1, 0), task(2, 102, 1, 2)));
        assertThat(sync.processBatch()).isTrue();
        when(tasks.succeed(anyLong(), anyInt())).thenReturn(0);
        assertThat(sync.processBatch()).isFalse();
    }

    @Test
    void olderProjectionMustNotBeWritten() throws Exception {
        when(projections.assemble(List.of(101L))).thenReturn(List.of(document(101, 1)));
        sync.processBatch();
        verify(writer, never())
                .writeBatch(argThat(documents -> documents.contains(document(101, 1))));
        verify(tasks).fail(1, 0, 5, 30000, "IllegalStateException");
    }

    @Test
    void overlappingCallsDoNotScanTwiceAndLockIsReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return results(invocation.getArgument(0), null);
                        })
                .when(writer)
                .writeBatch(anyList());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(sync::processBatch);
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            sync.processBatch();
            verify(tasks, times(1)).selectDue(20, 5);
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            sync.processBatch();
            verify(tasks, times(2)).selectDue(20, 5);
        } finally {
            release.countDown();
        }
    }

    @Test
    void missingItemAndUnexpectedTransportExceptionCannotConfirmTasks() {
        when(writer.writeBatch(anyList())).thenReturn(Map.of(101L, new WriteResult(null)));
        sync.processBatch();
        verify(tasks).succeed(1, 0);
        verify(tasks).fail(2, 2, 5, 30000, "IllegalStateException");
        clearInvocations(tasks);
        when(writer.writeBatch(anyList())).thenThrow(new IllegalStateException("unexpected"));
        sync.processBatch();
        verify(tasks, never()).succeed(anyLong(), anyInt());
        verify(tasks).fail(1, 0, 5, 30000, "IllegalStateException");
        verify(tasks).fail(2, 2, 5, 30000, "IllegalStateException");
    }

    private Map<Long, WriteResult> results(
            List<RestaurantSearchDocument> documents, Exception firstFailure) {
        Map<Long, WriteResult> results = new LinkedHashMap<>();
        for (var document : documents) {
            results.put(
                    document.getId(),
                    new WriteResult(document.getId() == 101 ? firstFailure : null));
        }
        return results;
    }

    static RestaurantSearchTask task(long id, long restaurant, long version, int attempts) {
        var task = new RestaurantSearchTask();
        task.setId(id);
        task.setRestaurantId(restaurant);
        task.setProjectionVersion(version);
        task.setAttempts(attempts);
        return task;
    }

    static RestaurantSearchDocument document(long id, long version) {
        var document = new RestaurantSearchDocument();
        document.setId(id);
        document.setProjectionVersion(version);
        document.setDeleted(true);
        return document;
    }
}
