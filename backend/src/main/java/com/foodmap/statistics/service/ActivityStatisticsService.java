package com.foodmap.statistics.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.statistics.config.ActivityStatisticsProperties;
import com.foodmap.statistics.entity.ActivityDailySummary;
import com.foodmap.statistics.exception.ActivityStatisticsErrorCode;
import com.foodmap.statistics.mapper.ActivityStatisticsMapper;
import com.foodmap.statistics.policy.ActivityTime;
import com.foodmap.statistics.storage.ActivityRedisStore;
import com.foodmap.statistics.vo.ActivityStatisticsVo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** 单实例采集协调器；串行协调跨日、状态及同步，数据库更新仍使用独立短事务和字段级SQL。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ActivityStatisticsService {
    public static final String STARTED = "activity_stats_started_at";
    private static final String LAST_SEEN = "activity_stats_last_seen_at";
    private static final String FINGERPRINT = "activity_stats_redis_fingerprint";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final ActivityStatisticsProperties properties;
    private final ActivityStatisticsMapper mapper;
    private final ActivityStatisticsWriter writer;
    private final ActivityRedisStore redis;
    private final ActivityTime time;
    private final Set<LocalDate> failedDates = new HashSet<>();
    private Instant started;
    private Instant lastObserved;
    private boolean initialized;

    public Instant now() {
        return time.now().truncatedTo(ChronoUnit.MICROS);
    }

    public synchronized boolean isCollecting() {
        return properties.isEnabled() && initialized;
    }

    /** 只由事务首次成功提交后的回调调用；失败不重放增量，尽力保留不完整状态。 */
    public synchronized void contribution(
            Instant occurredAt, boolean registration, Boolean eligible) {
        if (!isCollecting() || occurredAt.isBefore(started) || Boolean.FALSE.equals(eligible)) {
            return;
        }
        LocalDate date = date(occurredAt);
        try {
            if (eligible == null) {
                markPartial(date, date);
                return;
            }
            ensureDate(date, now(), continuous(now()) && date.equals(date(now())));
            writer.increment(date, registration);
        } catch (RuntimeException failure) {
            rememberFailure(date);
            log.warn("统计增量未确认，不重试计数，type={}", failure.getClass().getSimpleName());
        }
    }

    /** 身份取服务端认证结果；后台轮询和管理员访问不进入此入口。 */
    public synchronized void recordActive(long userId) {
        if (!isCollecting()) {
            return;
        }
        Instant now = now();
        LocalDate today = date(now);
        if (now.isBefore(started)) {
            throw new BusinessException(ActivityStatisticsErrorCode.UNAVAILABLE);
        }
        try {
            ensureDate(today, now, continuous(now));
            redis.record(today, userId);
        } catch (RuntimeException failure) {
            rememberFailure(today);
            throw new BusinessException(ActivityStatisticsErrorCode.UNAVAILABLE);
        }
    }

    /** 首次启用、固定间隔同步及保留期清理；不回补业务内容，不依赖管理员打开页面。 */
    public synchronized void maintain() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = now();
        LocalDate today = date(now);
        if (initialized && now.isBefore(started)) {
            markPartial(date(started), date(lastObserved));
            throw new IllegalStateException("Statistics clock is before collection start");
        }
        if (!initialized) {
            String previousStart = mapper.metadata(STARTED);
            started = writer.start(now);
            if (started.isAfter(now)) {
                throw new IllegalStateException("Statistics clock is before collection start");
            }
            if (previousStart != null) {
                String seen = mapper.metadata(LAST_SEEN);
                LocalDate first = seen == null ? date(started) : date(Instant.parse(seen));
                markGap(first, today);
            }
            initialized = true;
            lastObserved = now;
        } else if (!continuous(now)) {
            markGap(date(lastObserved), today);
        }
        for (LocalDate date : Set.copyOf(failedDates)) {
            markPartial(date, date);
            failedDates.remove(date);
        }
        ensureDate(today, now, continuous(now));
        try {
            checkRedisIntegrity(today);
            synchronize(today, now);
            // 跨日后补存昨天；从不读取已过期的90天历史集合来覆盖持久汇总。
            if (!today.minusDays(1).isBefore(date(started))) {
                synchronize(today.minusDays(1), now);
            }
        } catch (RuntimeException failure) {
            markPartial(today.minusDays(6), today);
            log.warn("活跃同步无法确认完整性，保留已有数值，type={}", failure.getClass().getSimpleName());
        }
        mapper.deleteBefore(today.minusYears(1));
        writer.saveMetadata(LAST_SEEN, now.toString());
        lastObserved = now;
    }

    /** 元数据只存采集运行标识，不包含用户记录；重启/淘汰按可能影响的90天保守降级。 */
    private boolean checkRedisIntegrity(LocalDate today) {
        String fingerprint = redis.fingerprint();
        String previous = mapper.metadata(FINGERPRINT);
        boolean changed = previous != null && !previous.equals(fingerprint);
        if (changed) {
            markPartial(today.minusDays(89), today);
        }
        if (previous == null || changed) {
            writer.saveMetadata(FINGERPRINT, fingerprint);
        }
        return changed;
    }

    private void synchronize(LocalDate date, Instant at) {
        if (mapper.day(date) != null) {
            writer.synchronizeActive(date, redis.count(date), at);
        }
    }

    private boolean continuous(Instant at) {
        return lastObserved != null
                && !at.isBefore(lastObserved)
                && Duration.between(lastObserved, at).compareTo(Duration.ofMinutes(2)) <= 0;
    }

    /** 正常连续采集的新日期可从0开始；历史缺口保持NULL，不从现存用户/点评倒推。 */
    private void ensureDate(LocalDate date, Instant at, boolean known) {
        if (mapper.day(date) != null) {
            return;
        }
        var row = new ActivityDailySummary();
        row.setStatDate(date);
        row.setCollectionStatus("UNKNOWN");
        if (known) {
            row.setNewUserCount(0L);
            row.setPublishedReviewCount(0L);
            row.setCollectionStatus("PARTIAL");
            try {
                row.setActiveUserCount(redis.count(date));
                row.setActiveSyncedAt(at);
                if (!date.equals(date(started))) {
                    row.setCollectionStatus("NORMAL");
                }
            } catch (RuntimeException failure) {
                // Redis不可用时仍允许新增/点评独立计数，活跃人数保持未知。
            }
        }
        writer.createDay(row);
    }

    private void markGap(LocalDate first, LocalDate last) {
        LocalDate lower = first.isAfter(last) ? last : first;
        if (lower.isBefore(last.minusYears(1))) {
            lower = last.minusYears(1);
        }
        if (lower.isBefore(date(started))) {
            lower = date(started);
        }
        for (LocalDate day = lower; !day.isAfter(last); day = day.plusDays(1)) {
            ensureDate(day, now(), false);
        }
        markPartial(lower, last);
    }

    private void markPartial(LocalDate first, LocalDate last) {
        writer.markPartial(first, last);
    }

    private void rememberFailure(LocalDate date) {
        // 有界保留日期，绝不保存待重试的加一事件；提交结果不明时重试可能重复计数。
        failedDates.removeIf(day -> day.isBefore(date(now()).minusYears(1)));
        failedDates.add(date);
        try {
            markPartial(date, date);
        } catch (RuntimeException ignored) {
            // 数据库恢复后由维护任务降级；进程崩溃窗口仍可能漏计，不能承诺完整恢复。
        }
    }

    public synchronized ActivityStatisticsVo read() {
        try {
            Instant now = now();
            LocalDate today = date(now);
            var result = new ActivityStatisticsVo();
            result.setCurrentDate(today);
            result.setFetchedAt(now);
            String value = mapper.metadata(STARTED);
            if (value == null) {
                return result;
            }
            Instant start = Instant.parse(value);
            if (start.isAfter(now)) {
                throw new IllegalStateException("Invalid collection start");
            }
            result.setEnabled(true);
            result.setStartedAt(start);
            var rows = mapper.days(today.minusDays(29), today);
            var days = new ArrayList<ActivityStatisticsVo.Day>();
            for (var row : rows) {
                if (row.getStatDate().isBefore(date(start))) {
                    continue;
                }
                var day = new ActivityStatisticsVo.Day();
                day.setDate(row.getStatDate());
                day.setActiveUserCount(safe(row.getActiveUserCount()));
                day.setNewUserCount(safe(row.getNewUserCount()));
                day.setPublishedReviewCount(safe(row.getPublishedReviewCount()));
                day.setCollectionStatus(row.getCollectionStatus());
                day.setActiveSyncedAt(row.getActiveSyncedAt());
                if (day.getDate().equals(today)
                        && (!isCollecting()
                                || day.getActiveSyncedAt() == null
                                || Duration.between(day.getActiveSyncedAt(), now)
                                                .compareTo(Duration.ofMinutes(2))
                                        > 0)) {
                    day.setCollectionStatus("PARTIAL");
                }
                days.add(day);
            }
            result.setDays(days);
            if (properties.isEnabled() && initialized) {
                try {
                    if (checkRedisIntegrity(today)) {
                        days.forEach(day -> day.setCollectionStatus("PARTIAL"));
                    }
                    long union = redis.union(today, date(start));
                    boolean complete = !date(start).isAfter(today.minusDays(6));
                    for (LocalDate day = today.minusDays(6);
                            !day.isAfter(today);
                            day = day.plusDays(1)) {
                        final LocalDate expected = day;
                        var row =
                                days.stream()
                                        .filter(item -> item.getDate().equals(expected))
                                        .findFirst()
                                        .orElse(null);
                        complete &= row != null && "NORMAL".equals(row.getCollectionStatus());
                        if (row != null
                                && row.getActiveUserCount() != null
                                && union < row.getActiveUserCount()) {
                            throw new IllegalStateException(
                                    "Activity union is below saved daily count");
                        }
                    }
                    result.setSevenDayActiveUserCount(safe(union));
                    result.setSevenDayCollectionStatus(complete ? "NORMAL" : "PARTIAL");
                } catch (RuntimeException failure) {
                    rememberFailure(today);
                    days.stream()
                            .filter(day -> !day.getDate().isBefore(today.minusDays(6)))
                            .forEach(day -> day.setCollectionStatus("PARTIAL"));
                    // 汇总仍可读；无法确认并集时返回NULL/UNKNOWN，不用每日人数相加兜底。
                }
            }
            return result;
        } catch (RuntimeException failure) {
            throw new BusinessException(ActivityStatisticsErrorCode.UNAVAILABLE);
        }
    }

    private Long safe(Long value) {
        if (value != null && (value < 0 || value > MAX_SAFE_INTEGER)) {
            throw new IllegalStateException("Statistics value is outside JSON safe integer range");
        }
        return value;
    }

    private static LocalDate date(Instant instant) {
        return instant.atZone(ActivityRedisStore.ZONE).toLocalDate();
    }
}
