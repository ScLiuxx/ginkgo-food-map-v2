package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.cache.RestaurantRankingCache;
import com.ginkgo.foodmap.restaurant.cache.RestaurantRankingLocalCache;
import com.ginkgo.foodmap.restaurant.cache.RestaurantRankingRedisStore;
import com.ginkgo.foodmap.restaurant.config.RestaurantRankingProperties;
import com.ginkgo.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.ginkgo.foodmap.restaurant.support.RestaurantRankingKey;
import com.ginkgo.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 使用可控时钟和真实线程验证同一把锁、回填TTL、失败绕过与有限恢复，不用计时推测并发次序。 */
class RestaurantRankingCacheTest {
    private static final RestaurantRankingKey KEY = RestaurantRankingKey.ALL.getFirst();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T08:00:00Z"));
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    private final AtomicInteger selects = new AtomicInteger();
    private RestaurantRankingRedisStore store;
    private RestaurantRankingSnapshotCodec codec;
    private RestaurantRankingProperties properties;
    private Clock clock;
    private RestaurantRankingCache cache;
    private RestaurantRankingLocalCache local;
    private RestaurantRankingSnapshotEntity row;

    @BeforeEach
    void setup() {
        store = mock(RestaurantRankingRedisStore.class);
        codec = new RestaurantRankingSnapshotCodec(JsonMapper.builder().build());
        properties = new RestaurantRankingProperties();
        properties.setCaffeineEnabled(false);
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        cache = coordinator();
        when(store.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0, String.class)));
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            ttls.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(store).set(anyString(), anyString(), any());
        deleteWorks();
        row = row(KEY);
    }

    @Test
    void coldReadFillsFullJsonAndSecondReadDoesNotQueryOrRenew() {
        assertThat(read().getStatus()).isEqualTo("READY");
        now.set(now.get().plusSeconds(60));
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(1);
        assertThat(ttls.get(key())).isEqualTo(Duration.ofHours(2));
        var json = JsonMapper.builder().build().readTree(values.get(key()));
        assertThat(json.get("rulesSnapshot").isObject()).isTrue();
        assertThat(json.get("items").isArray()).isTrue();
        assertThat(json.get("windowEndAt").stringValue()).isEqualTo(row.getWindowEndAt().toString());
        verify(store, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void successfulEmptyBoardUsesNormalTtl() {
        assertThat(read().getItems()).isEmpty();
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(1);
        assertThat(ttls.get(key())).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void preparingIsShortLivedAndChecksItsOwnAgeEvenIfRedisRetainsTheValue() {
        row = null;
        assertThat(read().getStatus()).isEqualTo("PREPARING");
        assertThat(read().getStatus()).isEqualTo("PREPARING");
        assertThat(selects.get()).isEqualTo(1);
        assertThat(ttls.get(key())).isEqualTo(Duration.ofSeconds(5));
        row = row(KEY);
        now.set(now.get().plusSeconds(5));
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void ttlIsCappedByRemainingDataAgeRatherThanGenerationTime() {
        row.setWindowEndAt(now.get().minus(Duration.ofHours(24)).plusSeconds(20));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(now.get().minusSeconds(1));
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(ttls.get(key())).isEqualTo(Duration.ofSeconds(20));
        now.set(now.get().plusSeconds(21));
        assertThat(read().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(values).doesNotContainKey(key());
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void exactlyMaximumAgeDoesNotWriteAZeroOrRenewedTtl() {
        row.setWindowEndAt(now.get().minus(Duration.ofHours(24)));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(row.getWindowEndAt());
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(values).isEmpty();
        now.set(now.get().plusNanos(1));
        assertThat(read().getStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void corruptOrCrossBoardCacheIsDeletedAndRebuiltFromMysql() {
        cache.recover();
        for (String invalid : new String[] {"broken", "{}", "null",
                codec.encodeCache(row(RestaurantRankingKey.ALL.get(2)), RestaurantRankingKey.ALL.get(2), now.get())}) {
            values.put(key(), invalid);
            assertThat(read().getStatus()).isEqualTo("READY");
            assertThat(values.get(key())).isNotEqualTo(invalid);
        }
        assertThat(selects.get()).isEqualTo(4);
    }

    @Test
    void redisReadFailureFallsBackAndCooldownAvoidsRepeatedTimeouts() {
        cache.recover();
        when(store.get(anyString())).thenThrow(unavailable());
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(read().getStatus()).isEqualTo("READY");
        verify(store, times(1)).get(anyString());
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void timedOutSetNeverMakesSubsequentReadsTrustAnUnknownOldValue() {
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            throw unavailable();
        }).when(store).set(anyString(), anyString(), any());
        assertThat(read().getStatus()).isEqualTo("READY");
        row.setItems("[]");
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(2);
        verify(store, times(1)).get(anyString());
    }

    @Test
    void failedPublicationDeleteBypassesOldRedisUntilSuccessfulInvalidation() {
        read();
        String old = values.get(key());
        doThrow(unavailable()).when(store).delete(any());
        cache.publishLocked(() -> {
            row.setWindowEndAt(row.getWindowEndAt().plusSeconds(30));
            row.setWindowStartAt(row.getWindowStartAt().plusSeconds(30));
            row.setGeneratedAt(row.getGeneratedAt().plusSeconds(30));
            cache.invalidateLocked(java.util.Set.of(KEY));
            return null;
        });
        assertThat(values.get(key())).isEqualTo(old);
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(selects.get()).isEqualTo(2);
        deleteWorks();
        now.set(now.get().plusSeconds(5));
        cache.recover();
        assertThat(values).doesNotContainKey(key());
        read();
        read();
        assertThat(selects.get()).isEqualTo(3);
    }

    @Test
    void restartClearsStaleValuesEvenBeforeReadyEvent() {
        read();
        row.setWindowEndAt(row.getWindowEndAt().plusSeconds(10));
        row.setWindowStartAt(row.getWindowStartAt().plusSeconds(10));
        row.setGeneratedAt(row.getGeneratedAt().plusSeconds(10));
        cache = coordinator();
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void repairsAreBoundedAndDoNotResetOnEveryRead() {
        doThrow(unavailable()).when(store).delete(any());
        read();
        read();
        now.set(now.get().plusSeconds(5));
        cache.recover();
        now.set(now.get().plusSeconds(5));
        cache.recover();
        now.set(now.get().plusSeconds(59));
        read();
        verify(store, times(3)).delete(any());
        now.set(now.get().plusSeconds(1));
        cache.recover();
        verify(store, times(4)).delete(any());
    }

    @Test
    void sixteenConcurrentColdReadersOnlyLoadMysqlOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = java.util.stream.IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return read();
            })).toList();
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(3, TimeUnit.SECONDS).getStatus()).isEqualTo("READY");
            }
        }
        assertThat(selects.get()).isEqualTo(1);
        verify(store, times(1)).set(anyString(), anyString(), any());
    }

    @Test
    void publishingWaitsForOldLoadThenDeletesItBeforeAllowingNextRead() throws Exception {
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.read(KEY, () -> {
                loading.countDown();
                await(release);
                return row;
            }));
            assertThat(loading.await(2, TimeUnit.SECONDS)).isTrue();
            var publication = executor.submit(() -> cache.publishLocked(() -> {
                cache.invalidateLocked(java.util.Set.of(KEY));
                return null;
            }));
            try {
                assertThatThrownBy(() -> publication.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            first.get(2, TimeUnit.SECONDS);
            publication.get(2, TimeUnit.SECONDS);
            assertThat(values).doesNotContainKey(key());
        }
    }

    @Test
    void differentBoardsAlsoShareOneLockAndWaitingIsBounded() throws Exception {
        properties.setLockWait(Duration.ofMillis(30));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> cache.read(KEY, () -> {
                held.countDown();
                await(release);
                return row;
            }));
            assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> cache.read(RestaurantRankingKey.ALL.get(1), () -> {
                    throw new AssertionError("Must not read Mysql outside lock");
                })).isInstanceOf(BusinessException.class);
            } finally {
                release.countDown();
            }
            first.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void ambientTransactionsAreRejectedBeforeAnyCachedOrMysqlRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);
            assertThat(selects.get()).isZero();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private com.ginkgo.foodmap.restaurant.vo.RestaurantRankingVo read() {
        return cache.read(KEY, () -> {
            selects.incrementAndGet();
            return row;
        });
    }

    @Test
    void caffeineHitSkipsRedisAndMysqlAndDoesNotRenewThirtyMinuteLifetime() {
        properties.setCaffeineEnabled(true);
        read();
        now.set(now.get().plus(Duration.ofMinutes(29)));
        read();
        verify(store, times(1)).get(anyString());
        now.set(now.get().plus(Duration.ofMinutes(1)));
        read();
        // 本机30分钟到期后只重新读Redis，不查库或重写Redis TTL。
        verify(store, times(2)).get(anyString());
        verify(store, times(1)).set(anyString(), anyString(), any());
        assertThat(selects.get()).isEqualTo(1);
    }

    @Test
    void caffeineHitDoesNotWaitForPublicationLock() throws Exception {
        properties.setCaffeineEnabled(true);
        read();
        properties.setLockWait(Duration.ofMillis(20));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var publication = executor.submit(() -> cache.publishLocked(() -> {
                held.countDown();
                await(release);
                return null;
            }));
            assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(read().getStatus()).isEqualTo("READY");
                verify(store, times(1)).get(anyString());
            } finally {
                release.countDown();
            }
            publication.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void caffeineStoresImmutableCopiesAndNeverReturnsSharedMutableItems() {
        properties.setCaffeineEnabled(true);
        row.setItems("[{\"rank\":1,\"restaurantId\":\"1\",\"name\":\"原店名\","
                + "\"tasteScore\":4.5,\"participantCount\":3}]");
        var first = read();
        first.setStatus("changed");
        first.getItems().getFirst().setName("changed");
        var second = read();
        assertThat(second.getStatus()).isEqualTo("READY");
        assertThat(second.getItems().getFirst().getName()).isEqualTo("原店名");
        second.getItems().getFirst().setName("again");
        assertThat(read().getItems().getFirst().getName()).isEqualTo("原店名");
        verify(store, times(1)).get(anyString());
    }

    @Test
    void caffeineAgeCheckStopsOldDataEvenBeforeThirtyMinuteExpiry() {
        properties.setCaffeineEnabled(true);
        row.setWindowEndAt(now.get().minus(Duration.ofHours(24)).plusSeconds(10));
        row.setWindowStartAt(row.getWindowEndAt().minus(Duration.ofHours(168)));
        row.setGeneratedAt(row.getWindowEndAt());
        assertThat(read().getStatus()).isEqualTo("READY");
        now.set(now.get().plusSeconds(10));
        assertThat(read().getStatus()).isEqualTo("READY");
        now.set(now.get().plusNanos(1));
        assertThat(read().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(read().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(selects.get()).isEqualTo(3);
    }

    @Test
    void preparingCannotGainAnotherFiveSecondsWhenRedisRefillsCaffeine() {
        row = null;
        read();
        properties.setCaffeineEnabled(true);
        now.set(now.get().plusSeconds(4));
        assertThat(read().getStatus()).isEqualTo("PREPARING");
        assertThat(selects.get()).isEqualTo(1);
        now.set(now.get().plusSeconds(1));
        row = row(KEY);
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void slowRedisWriteCannotRenewPreparingLifetimeInCaffeine() {
        properties.setCaffeineEnabled(true);
        row = null;
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            now.set(now.get().plusSeconds(4));
            return null;
        }).when(store).set(anyString(), anyString(), any());
        assertThat(read().getStatus()).isEqualTo("PREPARING");
        now.set(now.get().plusSeconds(1));
        row = row(KEY);
        assertThat(read().getStatus()).isEqualTo("READY");
        assertThat(selects.get()).isEqualTo(2);
    }

    @Test
    void caffeineColdReadersRecheckUnderLockAndUseOnlyOneRedisGet() throws Exception {
        properties.setCaffeineEnabled(true);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var reads = java.util.stream.IntStream.range(0, 16).mapToObj(i -> executor.submit(() -> {
                await(start);
                return read();
            })).toList();
            start.countDown();
            for (var future : reads) {
                assertThat(future.get(3, TimeUnit.SECONDS).getStatus()).isEqualTo("READY");
            }
        }
        assertThat(selects.get()).isEqualTo(1);
        verify(store, times(1)).get(anyString());
    }

    @Test
    void successfulPublicationInvalidatesBothLevelsAndRefillsLatest() {
        properties.setCaffeineEnabled(true);
        read();
        row.setWindowEndAt(row.getWindowEndAt().plusSeconds(10));
        row.setWindowStartAt(row.getWindowStartAt().plusSeconds(10));
        cache.publishLocked(() -> {
            cache.invalidateLocked(java.util.Set.of(KEY));
            return null;
        });
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(selects.get()).isEqualTo(2);
        verify(store, times(2)).get(anyString());
    }

    @Test
    void failedDeleteRemovesOldCaffeineAndCachesLatestMysqlWhileBypassingRedis() {
        properties.setCaffeineEnabled(true);
        read();
        String old = values.get(key());
        doThrow(unavailable()).when(store).delete(any());
        row.setWindowEndAt(row.getWindowEndAt().plusSeconds(10));
        row.setWindowStartAt(row.getWindowStartAt().plusSeconds(10));
        cache.publishLocked(() -> {
            cache.invalidateLocked(java.util.Set.of(KEY));
            return null;
        });
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(values.get(key())).isEqualTo(old);
        assertThat(selects.get()).isEqualTo(2);
        verify(store, times(1)).get(anyString());
    }

    @Test
    void caffeineAlsoCachesMysqlFallbackWhenRedisIsDownButNeverCachesFailures() {
        properties.setCaffeineEnabled(true);
        doThrow(unavailable()).when(store).delete(any());
        assertThatThrownBy(() -> cache.read(KEY, () -> { throw unavailable(); }))
                .isInstanceOf(DataAccessResourceFailureException.class);
        read();
        read();
        assertThat(selects.get()).isEqualTo(1);
    }

    @Test
    void disablingRedisDoesNotPreventCaffeineInvalidationAtPublication() {
        properties.setCaffeineEnabled(true);
        properties.setRedisEnabled(false);
        read();
        read();
        assertThat(selects.get()).isEqualTo(1);
        cache.publishLocked(() -> {
            cache.invalidateLocked(java.util.Set.of(KEY));
            return null;
        });
        read();
        assertThat(selects.get()).isEqualTo(2);
        verify(store, times(0)).get(anyString());
    }

    @Test
    void caffeineRestartCannotReusePreviousLocalOrRedisValues() {
        properties.setCaffeineEnabled(true);
        read();
        row.setWindowEndAt(row.getWindowEndAt().plusSeconds(10));
        row.setWindowStartAt(row.getWindowStartAt().plusSeconds(10));
        cache = coordinator();
        assertThat(read().getWindowEndAt()).isEqualTo(row.getWindowEndAt());
        assertThat(selects.get()).isEqualTo(2);
    }

    private RestaurantRankingCache coordinator() {
        local = new RestaurantRankingLocalCache(properties, clock);
        return new RestaurantRankingCache(Optional.of(store), codec, properties, clock, local);
    }

    private RestaurantRankingSnapshotEntity row(RestaurantRankingKey key) {
        var value = new RestaurantRankingSnapshotEntity();
        value.setCampusCode(key.campusCode());
        value.setPeriodType(key.periodType());
        value.setWindowEndAt(now.get().minusSeconds(120));
        value.setWindowStartAt(value.getWindowEndAt().minus(Duration.ofHours(key.windowHours())));
        value.setGeneratedAt(value.getWindowEndAt().plusSeconds(30));
        value.setRulesSnapshot("{\"formatVersion\":1,\"scoreScale\":5}");
        value.setItems("[]");
        return value;
    }

    private String key() {
        return cache.redisKey(KEY);
    }

    private void deleteWorks() {
        doAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            keys.forEach(values::remove);
            return null;
        }).when(store).delete(any());
    }

    private static DataAccessResourceFailureException unavailable() {
        return new DataAccessResourceFailureException("test-only failure");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
