package com.ginkgo.foodmap.restaurant;

import static org.mockito.Mockito.*;

import com.ginkgo.foodmap.restaurant.config.RestaurantSearchSyncProperties;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSearchDocument;
import com.ginkgo.foodmap.restaurant.entity.RestaurantSearchTask;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantSearchOutboxMapper;
import com.ginkgo.foodmap.restaurant.search.RestaurantSearchIndexWriter;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchProjectionService;
import com.ginkgo.foodmap.restaurant.service.RestaurantSearchSyncService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
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
    private final RestaurantSearchSyncService sync =
            new RestaurantSearchSyncService(tasks, projections, writer, properties);

    @BeforeEach
    void prepare() {
        when(tasks.selectDue(20, 5)).thenReturn(List.of(task(1, 101, 2, 0), task(2, 102, 1, 2)));
        when(projections.assemble(List.of(101L))).thenReturn(List.of(document(101, 3)));
        when(projections.assemble(List.of(102L))).thenReturn(List.of(document(102, 1)));
        when(tasks.succeed(anyLong(), anyInt())).thenReturn(1);
        when(tasks.fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString())).thenReturn(1);
    }

    @Test
    void writesFreshProjectionThenConfirmsOnlySpecificTasks() throws Exception {
        sync.processBatch();
        verify(writer).write(document(101, 3));
        verify(tasks).succeed(1, 0);
        verify(tasks).succeed(2, 2);
        verify(tasks, never()).fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    void failedItemGetsRetryAndDoesNotBlockNextItem() throws Exception {
        doThrow(new IOException("private source must not be recorded"))
                .when(writer)
                .write(document(101, 3));
        sync.processBatch();
        verify(tasks).fail(1, 0, 5, 30000, "IOException");
        verify(tasks, never()).succeed(1, 0);
        verify(tasks).succeed(2, 2);
    }

    @Test
    void failedAcknowledgementDoesNotTurnSuccessfulEsWriteIntoRetryFailure() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(tasks).succeed(1, 0);
        sync.processBatch();
        verify(tasks, never()).fail(anyLong(), anyInt(), anyInt(), anyLong(), anyString());
        verify(tasks).succeed(2, 2);
    }

    @Test
    void olderProjectionMustNotBeWritten() throws Exception {
        when(projections.assemble(List.of(101L))).thenReturn(List.of(document(101, 1)));
        sync.processBatch();
        verify(writer, never()).write(document(101, 1));
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
                            return null;
                        })
                .when(writer)
                .write(document(101, 3));
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
