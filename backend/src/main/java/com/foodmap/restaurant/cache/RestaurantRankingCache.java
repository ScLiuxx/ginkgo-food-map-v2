package com.foodmap.restaurant.cache;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.config.RestaurantRankingProperties;
import com.foodmap.restaurant.entity.RestaurantRankingSnapshotEntity;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.support.RestaurantRankingKey;
import com.foodmap.restaurant.support.RestaurantRankingSnapshotCodec;
import com.foodmap.restaurant.vo.RestaurantRankingVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单实例Cache Aside及发布协调。一把固定本地锁覆盖四榜加载、实际事务提交和失效。
 * Caffeine命中直接返回；dirty只表示绕过Redis，本机只缓存已校验且未超过数据年龄的结果。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestaurantRankingCache {
    private final Optional<RestaurantRankingRedisStore> store;
    private final RestaurantRankingSnapshotCodec codec;
    private final RestaurantRankingProperties properties;
    private final Clock restaurantRankingClock;
    private final RestaurantRankingLocalCache local;
    private final ReentrantLock lock = new ReentrantLock();
    // 每次进程启动都先标记四榜待清理；首次读取也受门禁保护，不依赖就绪事件先后。
    private final Set<RestaurantRankingKey> dirty = new LinkedHashSet<>(RestaurantRankingKey.ALL);
    private Instant nextRepairAt = Instant.EPOCH;
    private int repairAttempt;

    /** 等待者获得同一把锁后重新GET，冷键只由首个线程回源；故障时读取已提交MySQL快照。 */
    public RestaurantRankingVo read(RestaurantRankingKey key, Supplier<RestaurantRankingSnapshotEntity> mysql) {
        requireNoAmbientTransaction();
        var hit = local.get(key);
        if (hit != null) {
            return hit;
        }
        if (!enabled() && !properties.isCaffeineEnabled()) {
            return codec.view(mysql.get(), key, restaurantRankingClock.instant(), properties.getMaxSnapshotAge());
        }
        return locked(() -> {
            // 首个加载者可能已回填，等待者必须在得锁后复查，避免串行重复访问下层。
            var rechecked = local.get(key);
            if (rechecked != null) {
                return rechecked;
            }
            if (enabled()) {
                repairIfDue();
            }
            if (enabled() && !dirty.contains(key)) {
                try {
                    String value = store.orElseThrow().get(redisKey(key));
                    if (value != null) {
                        var now = restaurantRankingClock.instant();
                        var decoded = codec.decodeCache(value, key, now, properties.getPreparingTtl());
                        var row = decoded.row();
                        var view = codec.view(row, key, now, properties.getMaxSnapshotAge());
                        if (!"UNAVAILABLE".equals(view.getStatus())) {
                            local.put(key, view, decoded.preparingExpiresAt());
                            return view;
                        }
                        invalidateLocked(Set.of(key));
                    }
                } catch (DataAccessException exception) {
                    failed(Set.copyOf(RestaurantRankingKey.ALL), exception);
                } catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
                    // 损坏或跨榜数据不透传、不伪装成功空榜，清理后只从MySQL重建。
                    invalidateLocked(Set.of(key));
                }
            }
            var row = mysql.get();
            var loadedAt = restaurantRankingClock.instant();
            var view = codec.view(row, key, loadedAt, properties.getMaxSnapshotAge());
            var preparingExpiresAt = row == null ? loadedAt.plus(properties.getPreparingTtl()) : null;
            if (enabled() && !dirty.contains(key) && !"UNAVAILABLE".equals(view.getStatus())) {
                var now = restaurantRankingClock.instant();
                Duration ttl = row == null ? properties.getPreparingTtl()
                        : min(properties.getRedisTtl(), Duration.between(now,
                                row.getWindowEndAt().plus(properties.getMaxSnapshotAge())));
                if (ttl.toMillis() > 0) {
                    try {
                        store.orElseThrow().set(redisKey(key), codec.encodeCache(row, key, now), ttl);
                    } catch (DataAccessException exception) {
                        // 写入结果可能未知，之后先失效再恢复读取，本次仍返回MySQL结果。
                        failed(Set.copyOf(RestaurantRankingKey.ALL), exception);
                    }
                }
            }
            // 即使Redis故障，本机也可缓存刚读取的最新MySQL快照；下次发布仍会同步清除。
            local.put(key, view, preparingExpiresAt);
            return view;
        });
    }

    /** 发布服务在锁内完成TransactionTemplate实际提交后调用，回滚不得调用。 */
    public void invalidateLocked(Collection<RestaurantRankingKey> keys) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Ranking invalidation requires the publication lock");
        }
        local.invalidate(keys);
        if (!enabled()) {
            return;
        }
        dirty.addAll(keys);
        try {
            store.orElseThrow().delete(keys.stream().map(this::redisKey).toList());
            dirty.removeAll(keys);
        } catch (DataAccessException exception) {
            failed(keys, exception);
        }
    }

    /** 发布入口使用与加载相同的锁对象，禁止继承外层长事务的可见性。 */
    public <T> T publishLocked(Supplier<T> publication) {
        requireNoAmbientTransaction();
        return locked(publication);
    }

    /** 每轮最多一次DEL，失败按3次一轮、轮间冷却限速，不持锁睡眠。 */
    public void recover() {
        if (enabled()) {
            locked(() -> {
                repairIfDue();
                return null;
            });
        }
    }

    public String redisKey(RestaurantRankingKey key) {
        return properties.getRedisKeyPrefix() + key.suffix();
    }

    private boolean enabled() {
        return properties.isRedisEnabled() && store.isPresent();
    }

    private void repairIfDue() {
        if (dirty.isEmpty() || restaurantRankingClock.instant().isBefore(nextRepairAt)) {
            return;
        }
        var keys = Set.copyOf(dirty);
        try {
            store.orElseThrow().delete(keys.stream().map(this::redisKey).toList());
            dirty.clear();
            repairAttempt = 0;
            nextRepairAt = Instant.EPOCH;
        } catch (DataAccessException exception) {
            failed(keys, exception);
        }
    }

    private void failed(Collection<RestaurantRankingKey> keys, RuntimeException exception) {
        local.invalidate(keys);
        dirty.addAll(keys);
        repairAttempt++;
        Duration delay = repairAttempt >= properties.getRepairAttempts()
                ? properties.getRepairCooldown() : properties.getRepairDelay();
        if (repairAttempt >= properties.getRepairAttempts()) {
            repairAttempt = 0;
        }
        nextRepairAt = restaurantRankingClock.instant().plus(delay);
        log.warn("榜单Redis暂时绕过，type={}", exception.getClass().getSimpleName());
    }

    private <T> T locked(Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = lock.tryLock(properties.getLockWait().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new BusinessException(RestaurantErrorCode.RANKING_UNAVAILABLE);
            }
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(RestaurantErrorCode.RANKING_UNAVAILABLE);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BusinessException(RestaurantErrorCode.RANKING_UNAVAILABLE,
                    "榜单读写须在独立调用中完成，请稍后重试");
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
